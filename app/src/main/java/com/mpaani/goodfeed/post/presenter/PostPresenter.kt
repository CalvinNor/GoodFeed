package com.mpaani.goodfeed.post.presenter

import android.content.Context
import android.support.annotation.VisibleForTesting
import com.mpaani.goodfeed.R
import com.mpaani.goodfeed.core.data.ApiProxy
import com.mpaani.goodfeed.core.data.model.Comment
import com.mpaani.goodfeed.core.data.model.Post
import com.mpaani.goodfeed.core.data.model.User
import com.mpaani.goodfeed.core.db.DataProxy
import com.mpaani.goodfeed.core.event.Events
import com.mpaani.goodfeed.core.injection.dependencyComponent
import com.mpaani.goodfeed.post.PostPresenterContract
import com.mpaani.goodfeed.post.PostViewContract
import com.mpaani.goodfeed.post.event.CommentsEvent
import com.mpaani.goodfeed.post.event.PostEvent
import com.mpaani.goodfeed.post.event.UserEvent
import com.mpaani.goodfeed.post.transformer.getCommentsViewModels
import com.mpaani.goodfeed.post.transformer.getPostViewModel
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.lang.ref.WeakReference
import java.util.*
import javax.inject.Inject

/**
 * Presenter logic for Posts.
 * This class internally handles API access, DB access and presenting to the UI.
 */
class PostPresenter : PostPresenterContract {

    @Inject
    protected lateinit var apiProxy: ApiProxy

    @Inject
    protected lateinit var dataProxy: DataProxy

    @Inject
    protected lateinit var appContext: Context

    private lateinit var postView: WeakReference<PostViewContract>
    private var userModel: User? = null
    private var postModel: Post? = null
    private var commentsList: MutableList<Comment> = ArrayList()

    private var postId: Int = 0
    private var userEmail: String = ""

    private var fetchFromServerComplete = false
    private var fetchedUser = false

    private constructor(postId: Int, userEmail: String) {
        this.postId = postId
        this.userEmail = userEmail
    }

    constructor(postViewContract: PostViewContract,
                postId: Int,
                userEmail: String) : this(postId, userEmail) {

        dependencyComponent.inject(this)
        postView = WeakReference<PostViewContract>(postViewContract)

        Events.subscribe(this)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    constructor(feedViewContract: PostViewContract,
                postId: Int,
                userEmail: String,
                apiProxy: ApiProxy,
                dataProxy: DataProxy,
                appContext: Context) : this(postId, userEmail) {

        postView = WeakReference<PostViewContract>(feedViewContract)

        this.apiProxy = apiProxy
        this.dataProxy = dataProxy
        this.appContext = appContext

        Events.subscribe(this)
    }

    override fun fetchPost() {
        dataProxy.getPostById(postId)
        dataProxy.getUserByEmail(userEmail)
    }

    override fun fetchComments() {
        internalFetchComments()
    }

    override fun onExit() {
        Events.unsubscribe(this)
    }

    private fun internalFetchComments() {
        // Fetch from DB first
        dataProxy.getComments()

        // Try to fetch from API
        apiProxy.getComments().enqueue(object : Callback<List<Comment>> {

            override fun onFailure(call: Call<List<Comment>>?, t: Throwable?) {
                postView()?.onError(appContext.getString(R.string.feed_cannot_fetch_api))
            }

            override fun onResponse(call: Call<List<Comment>>?, response: Response<List<Comment>>?) {
                if (response?.body() == null) {
                    postView()?.onError(appContext.getString(R.string.feed_cannot_fetch_api))
                    return
                }

                fetchFromServerComplete = true

                val commentsList = response.body()!!
                dataProxy.insertComments(commentsList) // Cache

                val commentsForThisPost = getCommentsForThisPost(commentsList)
                populateCommentsList(commentsForThisPost)
                convertToCommentsViewModel()
            }
        })
    }

    private fun populateCommentsList(comments: List<Comment>) {
        commentsList.apply {
            clear()
            addAll(comments)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUserReceived(userEvent: UserEvent) {
        fetchedUser = true
        Events.removeSticky(userEvent)
        userModel = userEvent.user
        convertToPostViewModel()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPostReceived(postEvent: PostEvent) {
        Events.removeSticky(postEvent)
        postModel = postEvent.post
        convertToPostViewModel()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onCommentsReceived(commentsEvent: CommentsEvent) {
        if (fetchFromServerComplete) return

        val commentsForThisPost = getCommentsForThisPost(commentsEvent.commentsList)
        populateCommentsList(commentsForThisPost)
        convertToCommentsViewModel()
    }

    private fun getCommentsForThisPost(commentsList: List<Comment>) = commentsList.filter { it.postId == postId }

    private fun convertToPostViewModel() {
        if (postModel == null || !fetchedUser) return

        val postViewModel = getPostViewModel(appContext, userModel, postModel!!)
        postView()?.onPostReceived(postViewModel)
    }

    private fun convertToCommentsViewModel() {
        if (commentsList.isEmpty()) return

        val commentViewModels = getCommentsViewModels(commentsList)
        postView()?.onCommentsReceived(commentViewModels)
    }

    private fun postView(): PostViewContract? = postView.get()

}