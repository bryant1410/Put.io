package com.stevenschoen.putionew.files

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.stevenschoen.putionew.PutioApplication
import com.stevenschoen.putionew.R
import com.stevenschoen.putionew.UIUtils
import com.stevenschoen.putionew.model.files.PutioFile
import com.trello.rxlifecycle.components.support.RxFragment
import com.trello.rxlifecycle.kotlin.bindToLifecycle
import retrofit2.adapter.rxjava.HttpException
import rx.android.schedulers.AndroidSchedulers
import java.util.*

open class FilesFragment : RxFragment() {

    companion object {
        val STATE_PAGES = "pages"
        val STATE_CURRENT_PAGE = "current_page"

        val EXTRA_FOLDER = "folder"

        fun newInstance(context: Context, folder: PutioFile?): FilesFragment {
            val args = Bundle()
            if (folder != null) {
                args.putParcelable(EXTRA_FOLDER, folder)
            }
            return Fragment.instantiate(context, FilesFragment::class.java.name, args) as FilesFragment
        }
    }

    open val canSelect = true
    open val choosingFolder = false
    open val showSearch = true
    open val showCreateFolder = true
    open val padForFab = true

    var callbacks: Callbacks? = null

    val pages = ArrayList<Page>()
    var currentPage: Page? = null
        get() = if (pagerView != null) {
            pages[pagerView!!.currentItem]
        } else {
            field
        }
    val fileListFragmentsAdapter by lazy { PageFragmentsPagerAdapter() }
    var pagerView: ViewPager? = null
    val pageChangeListener = PageChangeListener()
    var isSelecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        if (savedInstanceState != null) {
            pages.addAll(savedInstanceState.getParcelableArrayList(STATE_PAGES))
            if (savedInstanceState.containsKey(STATE_CURRENT_PAGE)) {
                currentPage = savedInstanceState.getParcelable(STATE_CURRENT_PAGE)
            }
        } else if (arguments.containsKey(EXTRA_FOLDER)) {
            pages.add(Page(arguments.getParcelable<PutioFile>(EXTRA_FOLDER)))
        } else {
            pages.add(Page(PutioFile.makeRootFolder(resources)))
        }
        fileListFragmentsAdapter.notifyDataSetChanged()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.newfiles, container, false).apply {
            pagerView = findViewById(R.id.newfiles_pager) as ViewPager
            pagerView!!.adapter = fileListFragmentsAdapter
            pagerView!!.addOnPageChangeListener(pageChangeListener)

            if (UIUtils.hasLollipop()) {
                val folderFrontElevation = resources.getDimension(R.dimen.folder_front_elevation)

                pagerView!!.setPageTransformer(true) { page, position ->
                    page.visibility = View.VISIBLE
                    if (position <= -1) {
                        page.translationZ = 0f
                    } else {
                        page.translationZ = (position + 1) * folderFrontElevation
                    }
                    if (position < 0) {
                        if (position == -1f) {
                            // To prevent the previous page from being clickable through the current page
                            page.visibility = View.INVISIBLE
                        } else {
                            page.translationX = page.width * (-position * 0.5f)
                        }
                    } else {
                        page.translationX = 0f
                    }
                }
            }
        }
    }

    fun findPageWithIndexForFragment(fragment: FileListFragment<*>): IndexedValue<Page>? {
        val idToCompare: Long

        when (fragment) {
            is FolderFragment -> idToCompare = Page.makeUniqueId(fragment.folder)
            is SearchFragment -> idToCompare = Page.makeUniqueId(fragment.query, fragment.parentFolder)
            else -> throw RuntimeException()
        }

        pages.forEachIndexed { index, page ->
            if (page.getUniqueId() == idToCompare) {
                return IndexedValue(index, page)
            }
        }
        return null
    }

    fun removePagesAfterIndex(position: Int, notifyAdapterIfChanged: Boolean) {
        var changed = false
        while (pages.lastIndex > position) {
            pages.removeAt(pages.lastIndex)
            changed = true
        }

        if (notifyAdapterIfChanged && changed) {
            fileListFragmentsAdapter.notifyDataSetChanged()
        }
    }

    fun addFile(file: PutioFile) {
        removePagesAfterIndex(pagerView!!.currentItem, false)

        val iter = pages.listIterator()
        var foundParentIndex = -1
        for (existingPage in iter) {
            if (existingPage.type == Page.Type.File) {
                if (existingPage.file!!.id == file.parentId) {
                    foundParentIndex = iter.previousIndex()
                    break
                }
            }
        }
        if (foundParentIndex != -1) {
            pages.add(foundParentIndex + 1, Page(file))
            removePagesAfterIndex(foundParentIndex + 1, false)
        } else {
            pages.add(Page(file))
        }

        fileListFragmentsAdapter.notifyDataSetChanged()
        pagerView!!.setCurrentItem(pages.lastIndex, true)
    }

    fun addSearch(query: String) {
        removePagesAfterIndex(pagerView!!.currentItem, false)
        pages.add(Page(query, pages.last().file!!))

        fileListFragmentsAdapter.notifyDataSetChanged()
        pagerView!!.setCurrentItem(pages.lastIndex, true)
    }

    fun goToFile(parentId: Long, id: Long) {
        var found = false
        var parentIndex = 0
        pages.listIterator().apply {
            while (hasNext()) {
                val nextPage = next()
                if (nextPage.type == Page.Type.Search) {
                    removePagesAfterIndex(previousIndex() - 1, false)
                    break
                } else {
                    if (nextPage.file!!.id == parentId) {
                        parentIndex = previousIndex()
                        var targetIndex = parentIndex
                        if (hasNext()) {
                            val childPage = next()
                            if (childPage.file!!.id == id) {
                                found = true
                                targetIndex = previousIndex()
                            }
                        }
                        removePagesAfterIndex(targetIndex, false)
                        break
                    }
                }
            }
        }
        if (!found) {
            removePagesAfterIndex(parentIndex, false)
            PutioApplication.get(context).putioUtils.restInterface.file(id)
                    .bindToLifecycle(this@FilesFragment)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ result ->
                        addFile(result.file)
                    }, { error ->
                        if (error is HttpException && error.code() == 404) {
                            Toast.makeText(context, R.string.filenotfound, Toast.LENGTH_LONG).show()
                        }
                    })
        }

        fileListFragmentsAdapter.notifyDataSetChanged()
        pagerView!!.setCurrentItem(pages.lastIndex, true)
    }

    fun goBack(goToLastPageIfNotSelected: Boolean): Boolean {
        if (pageChangeListener.isGoingBack) {
            if (pagerView!!.currentItem == 0) {
                return false
            } else {
                pageChangeListener.removeCount++
                pagerView!!.setCurrentItem(pagerView!!.currentItem - 1, true)
                return true
            }
        } else if (goToLastPageIfNotSelected && pagerView!!.currentItem != pages.lastIndex) {
            pagerView!!.setCurrentItem(pages.lastIndex, true)
            return true
        } else if (pages.size > 1) {
            pageChangeListener.isGoingBack = true
            pageChangeListener.removeCount++
            pagerView!!.setCurrentItem(pages.lastIndex - 1, true)
            return true
        } else {
            return false
        }
    }

    override fun onAttachFragment(childFragment: Fragment) {
        super.onAttachFragment(childFragment)

        when (childFragment) {
            is FolderFragment, is SearchFragment -> {
                childFragment as FileListFragment<FileListFragment.Callbacks>
                childFragment.callbacks = object : FileListFragment.Callbacks {
                    override fun onFileSelected(file: PutioFile) {
                        if (!choosingFolder || file.isFolder) {
                            addFile(file)
                        }
                    }
                    override fun onBackSelected() {
                        val pageWithIndex = findPageWithIndexForFragment(childFragment)
                        pageWithIndex?.let {
                            removePagesAfterIndex(it.index, true)
                        }
                        goBack(false)
                    }
                    override fun onSelectionStarted() {
                        isSelecting = true
                        callbacks?.onSelectionStarted()
                    }
                    override fun onSelectionEnded() {
                        isSelecting = false
                        callbacks?.onSelectionEnded()
                    }
                }
            }
            is FileDetailsFragment -> childFragment.callbacks = object : FileDetailsFragment.Callbacks {
                override fun onFileDetailsClosed(refreshParent: Boolean) {
                    goBack(false)
                    if (refreshParent) {
                        val parentListFragment = fileListFragmentsAdapter.getFragmentAt(pagerView!!.currentItem) as FileListFragment<*>
                        parentListFragment.refresh()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_PAGES, pages)
        outState.putParcelable(STATE_CURRENT_PAGE, currentPage)
    }

    inner class PageFragmentsPagerAdapter : FragmentPagerAdapter(childFragmentManager) {

        private val fragments = SparseArray<Fragment>()

        override fun getItem(position: Int): Fragment {
            val page = pages[position]
            when (page.type) {
                Page.Type.File -> {
                    val file = page.file!!
                    if (file.isFolder) {
                        return FolderFragment.newInstance(context, file, padForFab, canSelect, showSearch, showCreateFolder)
                    } else {
                        return FileDetailsFragment.newInstance(context, file)
                    }
                }
                Page.Type.Search -> {
                    return SearchFragment.newInstance(context, page.searchQuery!!, page.parentFolder!!, canSelect)
                }
            }
        }

        override fun instantiateItem(container: ViewGroup?, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            fragments.put(position, fragment)

            return fragment
        }

        override fun getItemPosition(obj: Any): Int {
            when (obj) {
                is FolderFragment -> {
                    for ((index, page) in pages.withIndex()) {
                        if (page.type == Page.Type.File && page.file!! == obj.folder) {
                            return index
                        }
                    }
                    return POSITION_NONE
                }
                is SearchFragment -> {
                    for ((index, page) in pages.withIndex()) {
                        if (page.type == Page.Type.Search &&
                                page.searchQuery!! == obj.query && page.parentFolder!! == obj.parentFolder) {
                            return index
                        }
                    }
                    return POSITION_NONE
                }
                else -> return POSITION_NONE
            }
        }

        override fun getItemId(position: Int): Long {
            return pages[position].getUniqueId()
        }

        override fun destroyItem(container: ViewGroup?, position: Int, `object`: Any?) {
            super.destroyItem(container, position, `object`)
            if (position >= count) {
                childFragmentManager.beginTransaction()
                        .remove(`object` as Fragment)
                        .commitNowAllowingStateLoss()
            }
            fragments.removeAt(position)
        }

        override fun getCount() = pages.size

        fun getFragmentAt(position: Int) = fragments[position]
    }

    inner class PageChangeListener : ViewPager.SimpleOnPageChangeListener() {
        var removeCount = 0
        var isGoingBack = false

        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            callbacks?.onCurrentFileChanged()
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager.SCROLL_STATE_IDLE) {
                if (isGoingBack) {
                    isGoingBack = false
                    while (removeCount > 0) {
                        pages.removeAt(pages.lastIndex)
                        removeCount--
                    }
                    fileListFragmentsAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    class Page : Parcelable {

        val type: Type
        var file: PutioFile? = null
        var searchQuery: String? = null
        var parentFolder: PutioFile? = null

        constructor(file: PutioFile) {
            this.type = Type.File
            this.file = file
        }

        constructor(searchQuery: String, parentFolder: PutioFile) {
            this.type = Type.Search
            this.searchQuery = searchQuery
            this.parentFolder = parentFolder
        }

        fun getUniqueId(): Long {
            when (type) {
                Type.File -> return makeUniqueId(file!!)
                Type.Search -> return makeUniqueId(searchQuery!!, parentFolder!!)
            }
        }

        constructor(source: Parcel) {
            this.type = Type.values()[source.readInt()]
            when (type) {
                Type.File -> this.file = source.readParcelable(PutioFile::class.java.classLoader)
                Type.Search -> {
                    this.searchQuery = source.readString()
                    this.parentFolder = source.readParcelable(PutioFile::class.java.classLoader)
                }
            }
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(type.ordinal)
            when (type) {
                Type.File -> dest.writeParcelable(file, flags)
                Type.Search -> {
                    dest.writeString(searchQuery)
                    dest.writeParcelable(parentFolder, flags)
                }
            }
        }

        override fun describeContents(): Int {
            return 0
        }

        enum class Type {
            File, Search
        }

        companion object {
            fun makeUniqueId(file: PutioFile): Long {
                return file.id
            }
            fun makeUniqueId(searchQuery: String, parentFolder: PutioFile): Long {
                return searchQuery.hashCode() + parentFolder.id
            }

            @JvmField val CREATOR: Parcelable.Creator<Page> = object : Parcelable.Creator<Page> {
                override fun createFromParcel(source: Parcel): Page {
                    return Page(source)
                }

                override fun newArray(size: Int): Array<Page?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    interface Callbacks {
        fun onSelectionStarted()
        fun onSelectionEnded()
        fun onCurrentFileChanged()
    }
}