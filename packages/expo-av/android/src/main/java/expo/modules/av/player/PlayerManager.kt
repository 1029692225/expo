package expo.modules.av.player

import android.net.Uri
import android.os.Handler
import android.util.Pair
import android.view.Surface
import expo.modules.av.AVManagerInterface
import expo.modules.av.AudioFocusNotAcquiredException
import expo.modules.av.Params
import expo.modules.av.Status
import expo.modules.av.audio.AudioEventHandler
import org.unimodules.core.Promise
import org.unimodules.core.arguments.ReadableArguments
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI
import java.util.*

class PlayerManager(private val player: ExpoPlayer, private val avModule: AVManagerInterface,
                    private val uri: Uri) : AudioEventHandler, ExpoPlayer.PlayerStateListener {

  private val mHandler = Handler()
  private val mProgressUpdater = ProgressUpdater(this)

  private var mFullscreenPresenter: FullscreenPresenter? = null
  private var mParamsUpdatedListener: ParamsUpdatedListener? = null
  private var mStatusUpdateListener: StatusUpdateListener? = null
  private var mErrorListener: ErrorListener? = null
  private var mVideoSizeUpdateListener: VideoSizeUpdateListener? = null

//  private var mPlayerStatus: PlayerStatus = PlayerStatus.unloadedPlayerStatus()

  init {
    player.setPlayerStateListener(this)
  }

  val videoWidthHeight: Pair<Int, Int>
    get() = player.videoWidthHeight

  //TODO: Uncomment when done with params

  val isPlaying: Boolean
    get() = status.isLoaded && status.isPlaying

  var params: Params = Params()
    private set

  val status: Status
    @Synchronized get() {
      return Status(
          isLoaded = player.loaded,
          durationInMillis = player.duration,
          positionMillis = player.currentPosition,
          isPlaying = player.playing,
          isBuffering = player.buffering,
          playableDurationMillis = player.playableDuration ?: 0
      )
    }

  val audioSessionId: Int
    get() = player.audioSessionId

  val isPresentedFullscreen: Boolean
    get() = mFullscreenPresenter!!.isBeingPresentedFullscreen

  private// do nothing, we'll return an empty list
  val httpCookiesList: List<HttpCookie>
    get() {
      if (avModule.moduleRegistry != null) {
        val cookieHandler = avModule.moduleRegistry.getModule(CookieHandler::class.java)
        if (cookieHandler != null) {
          try {
            val headersMap = cookieHandler.get(URI.create(uri.toString()), null)
            val cookies = headersMap["Cookie"]
            return if (cookies != null) {
              val httpCookies = ArrayList<HttpCookie>()
              for (cookieValue in cookies) {
                httpCookies.addAll(HttpCookie.parse(cookieValue))
              }
              httpCookies
            } else {
              emptyList()
            }
          } catch (e: IOException) {
            // TODO: This look nasty
          }

        }
      }
      return emptyList()
    }

  fun play() {
    this.params = params.copy(shouldPlay = true)
  }

  fun pause() {
    this.params = params.copy(shouldPlay = false)
  }

  fun seekTo(positionMillis: Int) {
    player.seekTo(positionMillis)
  }

  interface VideoSizeUpdateListener {
    fun onVideoSizeUpdate(videoWidthHeight: Pair<Int, Int>)
  }

  interface ErrorListener {
    fun onError(error: String)
  }

  interface LoadCompletionListener {
    fun onLoadSuccess(status: Status)

    fun onLoadError(error: String)
  }

  interface ParamsUpdatedListener {
    fun onParamsUpdate(params: Params?)
  }

  interface StatusUpdateListener {
    fun onStatusUpdate(status: Status?)
  }

  internal interface SetParamsCompletionListener {
    fun onSetStatusComplete()

    fun onSetStatusError(error: String)
  }

  interface FullscreenPresenter {
    val isBeingPresentedFullscreen: Boolean

    fun setFullscreenMode(isFullscreen: Boolean)
  }

  private inner class ProgressUpdater constructor(playerManager: PlayerManager) : Runnable {
    private val mPlayerDataWeakReference: WeakReference<PlayerManager> =
        WeakReference(playerManager)

    override fun run() {
      val playerManager = mPlayerDataWeakReference.get()
      playerManager?.callStatusUpdateListener()
      playerManager?.progressUpdateLoop()
    }
  }
  // Lifecycle

  fun load(arguments: ReadableArguments, loadCompletionListener: LoadCompletionListener) {
    this.player.load(uri, httpCookiesList, object : ExpoPlayer.LoadListener {
      override fun onLoaded() {
        setParams(params.update(arguments), object : SetParamsCompletionListener {
          override fun onSetStatusComplete() {
            loadCompletionListener.onLoadSuccess(status)
          }

          override fun onSetStatusError(error: String) {
            loadCompletionListener.onLoadSuccess(status)
          }
        })
      }

      override fun onLoadError(error: String) {
        loadCompletionListener.onLoadError(error)
      }
    })
  }

  fun setSurface(surface: Surface) {
    this.player.setSurface(surface, params.shouldPlay)
  }

  fun release() {
    stopUpdatingProgressIfNecessary()
    this.player.release()
  }

  // Status update listener

  private fun callStatusUpdateListenerWithStatus(status: Status?) {
    mStatusUpdateListener?.onStatusUpdate(status)
  }

  private fun callStatusUpdateListenerWithDidJustFinish() {
    val status = status
    // TODO: Correct!
    //    status.putBoolean(STATUS_DID_JUST_FINISH_KEY_PATH, true);
    callStatusUpdateListenerWithStatus(status)
  }

  private fun callStatusUpdateListener() {
    callStatusUpdateListenerWithStatus(status)
  }

  private fun stopUpdatingProgressIfNecessary() {
    mHandler.removeCallbacks(mProgressUpdater)
  }

  private fun progressUpdateLoop() {
    if (!player.continueUpdatingProgress || params.updateInterval <= 0) {
      stopUpdatingProgressIfNecessary()
    } else {
      mHandler.postDelayed(mProgressUpdater, params.updateInterval.toLong())
    }
  }

  private fun beginUpdatingProgressIfNecessary() {
    mHandler.post(mProgressUpdater)
  }

  fun setStatusUpdateListener(listener: ParamsUpdatedListener) {
    mParamsUpdatedListener = listener
    if (mParamsUpdatedListener != null) {
      beginUpdatingProgressIfNecessary()
    }
  }

  // Error listener

  fun setErrorListener(listener: ErrorListener) {
    mErrorListener = listener
  }

  // Status

  private fun shouldPlayerPlay(): Boolean {
    return params.shouldPlay && params.rate > 0.0
  }

  fun setParams(status: ReadableArguments, promise: Promise?) {
    try {
      val newParams = this.params.update(status)
      setParams(newParams, object : SetParamsCompletionListener {
        override fun onSetStatusComplete() {
          if (promise == null) {
            callStatusUpdateListener()
          } else {
            promise.resolve(newParams.toBundle())
          }
        }

        override fun onSetStatusError(error: String) {
          if (promise == null) {
            callStatusUpdateListener()
          } else {
            promise.reject("E_AV_SETSTATUS", error)
          }
        }
      })
    } catch (throwable: Throwable) {
      promise?.reject("E_AV_SETSTATUS", "Encountered an error while setting status!", throwable)
    }

  }

  private fun setParams(params: Params,
                        setParamsCompletionListener: SetParamsCompletionListener) {
    this.params = params

    player.looping = this.params.isLooping

    if (!shouldPlayerPlay()) {
      player.pauseImmediately()
    } else {
      try {
        avModule.acquireAudioFocus()
        updateVolumeMuteAndDuck()
        player.play(this.params.isMuted, this.params.rate, this.params.shouldCorrectPitch)

        avModule.abandonAudioFocusIfUnused()
      } catch (ex: AudioFocusNotAcquiredException) {
        avModule.abandonAudioFocusIfUnused()
        setParamsCompletionListener.onSetStatusError(ex.toString())
      }

    }
    setParamsCompletionListener.onSetStatusComplete()

  }

  // Video specific stuff

  fun setVideoSizeUpdateListener(videoSizeUpdateListener: VideoSizeUpdateListener) {
    mVideoSizeUpdateListener = videoSizeUpdateListener
  }

  fun setFullscreenPresenter(fullscreenPresenter: FullscreenPresenter) {
    mFullscreenPresenter = fullscreenPresenter
  }

  fun toggleFullscreen() {
    mFullscreenPresenter?.setFullscreenMode(!isPresentedFullscreen)
  }

  // AudioEventHandler

  override fun handleAudioFocusInterruptionBegan() {
    if (!this.params.isMuted) {
      pauseImmediately()
      stopUpdatingProgressIfNecessary()
    }
  }

  override fun handleAudioFocusGained() {
    try {
      acquireFocusAndPlay()
    } catch (e: AudioFocusNotAcquiredException) {
      // This is ok -- we might be paused or audio might have been disabled.
    }

  }

  override fun onPause() {
    pauseImmediately()
  }

  override fun pauseImmediately() {
    player.pauseImmediately()
  }

  override fun requiresAudioFocus(): Boolean {
    return player.playing && !this.params.isMuted
  }

  override fun updateVolumeMuteAndDuck() {
    if (player.loaded) {
      player.volume =
          avModule.getVolumeForDuckAndFocus(this.params.isMuted, this.params.volume)
    }
  }

  override fun onResume() {
    try {
      acquireFocusAndPlay()
    } catch (e: AudioFocusNotAcquiredException) {
      // Do nothing -- another app has audio focus for now, and handleAudioFocusGained() will be
      // called when it abandons it.
    }

  }

  override fun onCompleted() {
    callStatusUpdateListenerWithDidJustFinish()

    if (!player.looping) {
      avModule.abandonAudioFocusIfUnused()
    }
  }

  override fun onSeekCompleted() {
    callStatusUpdateListener()
  }

  override fun onError(message: String) {
    mErrorListener!!.onError(message)
  }

  override fun onBufferingStart() {
    callStatusUpdateListener()
  }

  override fun onBuffering(bufferedDuration: Int) {
    callStatusUpdateListener()
  }

  override fun onBufferingStop() {
    callStatusUpdateListener()
  }

  override fun statusUpdated() {
    callStatusUpdateListener()
  }

  override fun videoSizeChanged(width: Int, height: Int) {
    mVideoSizeUpdateListener?.onVideoSizeUpdate(Pair(width, height))
  }

  @Throws(AudioFocusNotAcquiredException::class)
  private fun acquireFocusAndPlay() {
    if (!player.loaded || !shouldPlayerPlay()) {
      return
    }

    if (!this.params.isMuted) {
      avModule.acquireAudioFocus()
    }

    updateVolumeMuteAndDuck()

    player.play(this.params.isMuted, this.params.rate, this.params.shouldCorrectPitch)

    beginUpdatingProgressIfNecessary()
  }

}
