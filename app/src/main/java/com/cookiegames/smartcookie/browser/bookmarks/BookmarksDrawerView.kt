package com.cookiegames.smartcookie.browser.bookmarks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.*
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.adblock.allowlist.AllowListModel
import com.cookiegames.smartcookie.animation.AnimationUtils
import com.cookiegames.smartcookie.browser.BookmarksView
import com.cookiegames.smartcookie.browser.DrawerSizeChoice
import com.cookiegames.smartcookie.browser.JavaScriptChoice
import com.cookiegames.smartcookie.browser.TabsManager
import com.cookiegames.smartcookie.controller.UIController
import com.cookiegames.smartcookie.database.Bookmark
import com.cookiegames.smartcookie.database.bookmark.BookmarkRepository
import com.cookiegames.smartcookie.di.DatabaseScheduler
import com.cookiegames.smartcookie.di.MainScheduler
import com.cookiegames.smartcookie.di.NetworkScheduler
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.dialog.DialogItem
import com.cookiegames.smartcookie.dialog.LightningDialogBuilder
import com.cookiegames.smartcookie.extensions.color
import com.cookiegames.smartcookie.extensions.drawable
import com.cookiegames.smartcookie.extensions.inflater
import com.cookiegames.smartcookie.favicon.FaviconModel
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.reading.activity.ReadingActivity
import com.cookiegames.smartcookie.utils.isSpecialUrl
import com.github.ahmadaghazadeh.editor.widget.CodeEditor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject


/**
 * The view that displays bookmarks in a list and some controls.
 */

//TODO: DOESN'T SAVE MOVED BOOKMARKS!
class BookmarksDrawerView @JvmOverloads constructor(
        context: Context,
        private val activity: Activity,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        userPreferences: UserPreferences
) : LinearLayout(context, attrs, defStyleAttr), BookmarksView {

    @Inject internal lateinit var bookmarkModel: BookmarkRepository
    @Inject internal lateinit var allowListModel: AllowListModel
    @Inject internal lateinit var bookmarksDialogBuilder: LightningDialogBuilder
    @Inject internal lateinit var faviconModel: FaviconModel
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject @field:NetworkScheduler internal lateinit var networkScheduler: Scheduler
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler

    private val uiController: UIController
    // Adapter
    private var bookmarkAdapter: BookmarkListAdapter? = null

    // Colors
    private var scrollIndex: Int = 0

    private var bookmarksSubscription: Disposable? = null
    private var bookmarkUpdateSubscription: Disposable? = null

    private val uiModel = BookmarkUiModel()

    private var bookmarkRecyclerView: RecyclerView? = null
    private var backNavigationView: ImageView? = null
    private var addBookmarkView: ImageView? = null

    private val itemTouchHelper by lazy {
        val simpleItemTouchCallback =
                object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or
                        ItemTouchHelper.DOWN or
                        ItemTouchHelper.START or
                        ItemTouchHelper.END, 0) {

                    override fun onMove(recyclerView: RecyclerView,
                                        viewHolder: RecyclerView.ViewHolder,
                                        target: RecyclerView.ViewHolder): Boolean {

                        val adapter = recyclerView.adapter as BookmarkListAdapter
                        val from = viewHolder.adapterPosition
                        val to = target.adapterPosition
                        adapter.moveItem(from, to)
                        adapter.notifyItemMoved(from, to)

                        return true
                    }    override fun onSwiped(viewHolder: RecyclerView.ViewHolder,
                                               direction: Int) {
                    }
                }

        ItemTouchHelper(simpleItemTouchCallback)
    }


    init {
        context.inflater.inflate(R.layout.bookmark_drawer, this, true)
        context.injector.inject(this)

        uiController = context as UIController

        bookmarkRecyclerView = findViewById(R.id.bookmark_list_view)
        backNavigationView = findViewById(R.id.bookmark_back_button)
        addBookmarkView = findViewById(R.id.action_add_bookmark)
        backNavigationView?.setOnClickListener {
            if (!uiModel.isCurrentFolderRoot()) {
                setBookmarksShown(null, true)
                bookmarkRecyclerView?.layoutManager?.scrollToPosition(scrollIndex)
            }
        }
        addBookmarkView?.setOnClickListener { uiController.bookmarkButtonClicked() }
        findViewById<View>(R.id.action_reading).setOnClickListener {
            getTabsManager().currentTab?.url?.let {
                ReadingActivity.launch(context, it, false)
            }
        }
        findViewById<View>(R.id.action_page_tools).setOnClickListener { showPageToolsDialog(context, userPreferences) }

        bookmarkAdapter = BookmarkListAdapter(
                context,
                faviconModel,
                networkScheduler,
                mainScheduler,
                ::handleItemLongPress,
                ::handleItemClick,
                userPreferences,
                uiController,
                bookmarkModel,
                databaseScheduler
        )

        itemTouchHelper.attachToRecyclerView(bookmarkRecyclerView)

        bookmarkRecyclerView?.let {
            it.layoutManager = LinearLayoutManager(context)
            it.adapter = bookmarkAdapter
        }

        //itemTouchHelper.attachToRecyclerView(bookmarkRecyclerView)

        setBookmarksShown(null, true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        bookmarksSubscription?.dispose()
        bookmarkUpdateSubscription?.dispose()

        bookmarkAdapter?.cleanupSubscriptions()
    }

    private fun getTabsManager(): TabsManager = uiController.getTabModel()

    private fun updateBookmarkIndicator(url: String) {
        bookmarkUpdateSubscription?.dispose()
        bookmarkUpdateSubscription = bookmarkModel.isBookmark(url)
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { isBookmark ->
                bookmarkUpdateSubscription = null
                addBookmarkView?.isSelected = isBookmark
                addBookmarkView?.isEnabled = !url.isSpecialUrl()
            }
    }

    override fun handleBookmarkDeleted(bookmark: Bookmark) = when (bookmark) {
        is Bookmark.Folder -> setBookmarksShown(null, false)
        is Bookmark.Entry -> bookmarkAdapter?.deleteItem(BookmarksViewModel(bookmark)) ?: Unit
    }

    private fun setBookmarksShown(folder: String?, animate: Boolean) {
        bookmarksSubscription?.dispose()
        bookmarksSubscription = bookmarkModel.getBookmarksFromFolderSorted(folder)
            .concatWith(Single.defer {
                if (folder == null) {
                    bookmarkModel.getFoldersSorted()
                } else {
                    Single.just(emptyList())
                }
            })
            .toList()
            .map { it.flatten() }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { bookmarksAndFolders ->
                uiModel.currentFolder = folder
                setBookmarkDataSet(bookmarksAndFolders, animate)
            }
    }

    private fun setBookmarkDataSet(items: List<Bookmark>, animate: Boolean) {
        bookmarkAdapter?.updateItems(items.map { BookmarksViewModel(it) })
        val resource = if (uiModel.isCurrentFolderRoot()) {
            R.drawable.ic_action_star
        } else {
            R.drawable.ic_action_back
        }

        if (animate) {
            backNavigationView?.let {
                val transition = AnimationUtils.createRotationTransitionAnimation(it, resource)
                it.startAnimation(transition)
            }
        } else {
            backNavigationView?.setImageResource(resource)
        }
    }

    private fun handleItemLongPress(bookmark: Bookmark): Boolean {
        (context as Activity?)?.let {
            when (bookmark) {
                is Bookmark.Folder -> bookmarksDialogBuilder.showBookmarkFolderLongPressedDialog(it, uiController, bookmark)
                is Bookmark.Entry -> bookmarksDialogBuilder.showLongPressedDialogForBookmarkUrl(it, uiController, bookmark)
            }
        }
        return true
    }

    private fun handleItemClick(bookmark: Bookmark) = when (bookmark) {
        is Bookmark.Folder -> {
            scrollIndex = (bookmarkRecyclerView?.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            setBookmarksShown(bookmark.title, true)
        }
        is Bookmark.Entry -> uiController.bookmarkItemClicked(bookmark)
    }

    fun stringContainsItemFromList(inputStr: String, items: Array<String>): Boolean {
        for (i in items.indices) {
            if (inputStr.contains(items[i])) {
                return true
            }
        }
        return false
    }
    /**
     * Show the page tools dialog.
     */
    private fun showPageToolsDialog(context: Context, userPreferences: UserPreferences) {
        val currentTab = getTabsManager().currentTab ?: return
        val isAllowedAds = allowListModel.isUrlAllowedAds(currentTab.url)
        val whitelistString = if (isAllowedAds) {
            R.string.dialog_adblock_enable_for_site
        } else {
            R.string.dialog_adblock_disable_for_site
        }
        val arrayOfURLs = userPreferences.javaScriptBlocked
        val strgs: Array<String>
        if (arrayOfURLs.contains(", ")) {
            strgs = arrayOfURLs.split(", ".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        } else {
            strgs = arrayOfURLs.split(",".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        }
        var jsEnabledString = if(userPreferences.javaScriptChoice == JavaScriptChoice.BLACKLIST && !stringContainsItemFromList(currentTab.url, strgs) || userPreferences.javaScriptChoice == JavaScriptChoice.WHITELIST && stringContainsItemFromList(currentTab.url, strgs)) {
            R.string.allow_javascript
        } else{
            R.string.block_javascript
        }


        BrowserDialog.showWithIcons(context, context.getString(R.string.dialog_tools_title),
                DialogItem(
                        icon = context.drawable(R.drawable.ic_action_desktop),
                        title = R.string.dialog_toggle_desktop
                ) {
                    getTabsManager().currentTab?.apply {
                        toggleDesktopUA()
                        reload()
                        // TODO add back drawer closing
                    }
                },
                DialogItem(
                        icon = context.drawable(R.drawable.ic_page_tools),
                        title = R.string.inspect

                ) {
                    val builder = AlertDialog.Builder(context)
                    val inflater = activity.layoutInflater
                    builder.setTitle(R.string.inspect)
                    val dialogLayout = inflater.inflate(R.layout.dialog_edit_text, null)
                    val editText = dialogLayout.findViewById<EditText>(R.id.dialog_edit_text)
                    builder.setView(dialogLayout)
                    builder.setPositiveButton("OK") { dialogInterface, i -> currentTab.loadUrl("javascript:(function() {" + editText.text.toString() + "})()") }
                    builder.show()

                }, DialogItem(
                icon = context.drawable(R.drawable.ic_round_storage_24),
                title = R.string.edit_cookies
        ) {

            val cookieManager = CookieManager.getInstance()
            if (cookieManager.getCookie(currentTab.url) != null) {
                val builder = MaterialAlertDialogBuilder(context)
                val inflater = activity.layoutInflater
                builder.setTitle(R.string.edit_cookies)
                val dialogLayout = inflater.inflate(R.layout.dialog_multi_line, null)
                val editText = dialogLayout.findViewById<EditText>(R.id.dialog_multi_line)
                editText.setText(cookieManager.getCookie(currentTab.url))
                builder.setView(dialogLayout)
                builder.setPositiveButton("OK") { dialogInterface, i ->
                    val cookiesList = editText.text.toString().split(";")
                    cookiesList.forEach { item ->
                        CookieManager.getInstance().setCookie(currentTab.url, item)
                    }
                }
                builder.show()
            }

        },
                DialogItem(
                        icon = context.drawable(R.drawable.ic_baseline_code_24),
                        title = R.string.page_source

                ) {
                    currentTab.webView?.evaluateJavascript("""(function() {
                        return "<html>" + document.getElementsByTagName('html')[0].innerHTML + "</html>";
                     })()""".trimMargin()) {
                        // Hacky workaround for weird WebView encoding bug
                        var name = it?.replace("\\u003C", "<")
                        name = name?.replace("\\n", System.getProperty("line.separator").toString())
                        name = name?.replace("\\t", "")
                        name = name?.replace("\\\"", "\"")
                        name = name?.substring(1, name.length - 1);

                        val builder = MaterialAlertDialogBuilder(context)
                        val inflater = activity.layoutInflater
                        builder.setTitle(R.string.page_source)
                        val dialogLayout = inflater.inflate(R.layout.dialog_view_source, null)
                        val editText = dialogLayout.findViewById<CodeEditor>(R.id.dialog_multi_line)
                        editText.setText(name, 1)
                        builder.setView(dialogLayout)
                        builder.setPositiveButton("OK") { _, _ ->
                            editText.setText(editText.text?.toString()?.replace("\'", "\\\'"), 1);
                            currentTab.loadUrl("javascript:(function() { document.documentElement.innerHTML = '" + editText.text.toString() + "'; })()")
                        }
                        builder.show()
                    }
                },
                DialogItem(
                        icon = context.drawable(R.drawable.ic_block),
                        colorTint = context.color(R.color.error_red).takeIf { isAllowedAds },
                        title = whitelistString,
                        isConditionMet = !currentTab.url.isSpecialUrl()
                ) {
                    if (isAllowedAds) {
                        allowListModel.removeUrlFromAllowList(currentTab.url)
                    } else {
                        allowListModel.addUrlToAllowList(currentTab.url)
                    }
                    getTabsManager().currentTab?.reload()
                }, DialogItem(
                icon = context.drawable(R.drawable.ic_action_delete),
                title = jsEnabledString,
                isConditionMet = !currentTab.url.isSpecialUrl()
        ) {
            val url = URL(currentTab.url)
            if (userPreferences.javaScriptChoice != JavaScriptChoice.NONE) {
                if (!stringContainsItemFromList(currentTab.url, strgs)) {
                    if (userPreferences.javaScriptBlocked.equals("")) {
                        userPreferences.javaScriptBlocked = url.host
                    } else {
                        userPreferences.javaScriptBlocked = userPreferences.javaScriptBlocked + ", " + url.host
                    }
                } else {
                    if (!userPreferences.javaScriptBlocked.contains(", " + url.host)) {
                        userPreferences.javaScriptBlocked = userPreferences.javaScriptBlocked.replace(url.host, "")
                    } else {
                        userPreferences.javaScriptBlocked = userPreferences.javaScriptBlocked.replace(", " + url.host, "")
                    }
                }
            } else {
                userPreferences.javaScriptChoice = JavaScriptChoice.WHITELIST
            }
            getTabsManager().currentTab?.reload()
            Handler().postDelayed({
                getTabsManager().currentTab?.reload()
            }, 250)

        }
        )
    }

    override fun navigateBack() {
        if (uiModel.isCurrentFolderRoot()) {
            uiController.onBackButtonPressed()
        } else {
            setBookmarksShown(null, true)
            bookmarkRecyclerView?.layoutManager?.scrollToPosition(scrollIndex)
        }
    }

    override fun handleUpdatedUrl(url: String) {
        updateBookmarkIndicator(url)
        val folder = uiModel.currentFolder
        setBookmarksShown(folder, false)
    }

    private class BookmarkViewHolder(
            itemView: View,
            private val adapter: BookmarkListAdapter,
            private val onItemLongClickListener: (Bookmark) -> Boolean,
            private val onItemClickListener: (Bookmark) -> Unit,
            private val userPreferences: UserPreferences
    ) : RecyclerView.ViewHolder(itemView), OnClickListener, OnLongClickListener {

        val txtTitle: TextView = itemView.findViewById(R.id.textBookmark)
        val favicon: ImageView = itemView.findViewById(R.id.faviconBookmark)
        val faviconButton: FrameLayout = itemView.findViewById(R.id.faviconButton)

        init {
            faviconButton.setOnClickListener(this)
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)

            txtTitle.maxLines = userPreferences.drawerLines.value + 1
            if(userPreferences.drawerSize != DrawerSizeChoice.AUTO){
                TextViewCompat.setAutoSizeTextTypeWithDefaults(txtTitle, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                txtTitle.setTextSize(userPreferences.drawerSize.value.toFloat() * 7)
            }
        }

        override fun onClick(v: View) {
            if(v == faviconButton){
                val index = adapterPosition
                if(index != RecyclerView.NO_POSITION){
                    onItemLongClickListener(adapter.itemAt(index).bookmark)
                }
            }else{
                val index = adapterPosition
                if (index.toLong() != RecyclerView.NO_ID) {
                    onItemClickListener(adapter.itemAt(index).bookmark)
                }
            }
        }

        override fun onLongClick(v: View): Boolean {
            return true
        }
    }


    private class BookmarkListAdapter(
            context: Context,
            private val faviconModel: FaviconModel,
            private val networkScheduler: Scheduler,
            private val mainScheduler: Scheduler,
            private val onItemLongClickListener: (Bookmark) -> Boolean,
            private val onItemClickListener: (Bookmark) -> Unit,
            private val userPreferences: UserPreferences,
            private val uiController: UIController,
            private val bookmarkManager: BookmarkRepository,
            private val databaseScheduler: Scheduler
    ) : RecyclerView.Adapter<BookmarkViewHolder>() {

        private var bookmarks: List<BookmarksViewModel> = listOf()
        private val faviconFetchSubscriptions = ConcurrentHashMap<String, Disposable>()
        private val folderIcon = context.drawable(R.drawable.ic_folder)
        private val webpageIcon = context.drawable(R.drawable.ic_webpage)

        fun itemAt(position: Int): BookmarksViewModel = bookmarks[position]

        fun deleteItem(item: BookmarksViewModel) {
            val newList = bookmarks - item
            updateItems(newList)
        }

        fun moveItem(from: Int, to: Int){
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(bookmarks, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(bookmarks, i, i - 1)
                }
            }

            bookmarks.forEachIndexed { index, element ->
                when(bookmarks[index].bookmark){
                    is Bookmark.Entry -> {
                        bookmarkManager.moveBookmark(bookmarks[index].bookmark as Bookmark.Entry, index)
                                .subscribeOn(databaseScheduler)
                                .observeOn(mainScheduler)
                                .subscribe {  }
                    }
                    is Bookmark.Folder -> {
                        iterateBookmarksInFolder(bookmarks[index].bookmark.title, index)
                    }
                }
            }
        }

        fun iterateBookmarksInFolder(name: String, position: Int){
            bookmarkManager.getBookmarksFromFolderSorted(name)
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe { bookmarksAndFolders ->
                        bookmarksAndFolders.forEachIndexed { index, element ->
                            bookmarkManager.moveBookmark(element as Bookmark.Entry, index + position)
                                    .subscribeOn(databaseScheduler)
                                    .observeOn(mainScheduler)
                                    .subscribe {  }
                        }
                    }
        }

        fun updateItems(newList: List<BookmarksViewModel>) {
            val oldList = bookmarks
            bookmarks = newList

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldList.size

                override fun getNewListSize() = bookmarks.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        oldList[oldItemPosition].bookmark.url == bookmarks[newItemPosition].bookmark.url

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        oldList[oldItemPosition] == bookmarks[newItemPosition]
            })

            diffResult.dispatchUpdatesTo(this)
        }

        fun cleanupSubscriptions() {
            for (subscription in faviconFetchSubscriptions.values) {
                subscription.dispose()
            }
            faviconFetchSubscriptions.clear()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemView = inflater.inflate(R.layout.bookmark_list_item, parent, false)
            return BookmarkViewHolder(itemView, this, onItemLongClickListener, onItemClickListener, userPreferences = userPreferences)
        }

        override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
            holder.itemView.jumpDrawablesToCurrentState()

            val viewModel = bookmarks[position]
            holder.txtTitle.text = viewModel.bookmark.title



            val url = viewModel.bookmark.url
            holder.favicon.tag = url

            viewModel.icon?.let {
                holder.favicon.setImageBitmap(it)
                return
            }

            val imageDrawable = when (viewModel.bookmark) {
                is Bookmark.Folder -> folderIcon
                is Bookmark.Entry -> webpageIcon.also {
                    faviconFetchSubscriptions[url]?.dispose()
                    faviconFetchSubscriptions[url] = faviconModel
                            .faviconForUrl(url, viewModel.bookmark.title)
                            .subscribeOn(networkScheduler)
                            .observeOn(mainScheduler)
                            .subscribeBy(
                                    onSuccess = { bitmap ->
                                        viewModel.icon = bitmap
                                        if (holder.favicon.tag == url) {
                                            holder.favicon.setImageBitmap(bitmap)
                                        }
                                    }
                            )
                }
            }

            holder.favicon.setImageDrawable(imageDrawable)
        }

        override fun getItemCount() = bookmarks.size
    }

}
