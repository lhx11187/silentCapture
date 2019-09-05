package com.dillon.silentcapture

import android.Manifest
import android.annotation.SuppressLint
import android.app.IntentService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Date
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max


class SilentPictureService : IntentService(TAG), TextureView.SurfaceTextureListener {
    companion object {
        private const val TAG = "SilentPictureService"

        //Conversion from screen rotation to JPEG orientation.
        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }


        //Camera state: Showing camera preview.
        private const val STATE_PREVIEW = 0

        // Camera state: Waiting for the focus to be locked.
        private const val STATE_WAITING_LOCK = 1

        // Camera state: Waiting for the exposure to be preCapture state.
        private const val STATE_WAITING_PRE_CAPTURE = 2

        //Camera state: Waiting for the exposure state to be something other than preCapture.
        private const val STATE_WAITING_NON_PRE_CAPTURE = 3

        //Camera state: Picture was taken.
        private const val STATE_PICTURE_TAKEN = 4

        //Max preview width that is guaranteed by Camera2 API
        private const val MAX_PREVIEW_WIDTH = 1920

        //Max preview height that is guaranteed by Camera2 API
        private const val MAX_PREVIEW_HEIGHT = 1080

        private fun chooseOptimalSize(
            choices: Array<Size>, textureViewWidth: Int,
            textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                    option.height == option.width * h / w
                ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return when {
                bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
                notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
                else -> {
                    Log.e(TAG, "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var isDestroy = false
    //拍照间隔30s
    private val capturePeriod = 30000
    //输出图片宽度px
    private val pictureWidth = 480
    ///输出图片高度px
    private val pictureHeight = 680
    //前置摄像头: CameraCharacteristics.LENS_FACING_FRONT，0
    //后摄像头为：CameraCharacteristics.LENS_FACING_BACK，1
    private val mFacing = CameraCharacteristics.LENS_FACING_FRONT

    private var alreadyOpen = false

    // ID of the current [CameraDevice].
    private var mCameraId: String? = null

    //An [MyTextureView] for camera preview.
    private var mTextureView: MyTextureView? = null

    //A [CameraCaptureSession] for camera preview.
    private var mCaptureSession: CameraCaptureSession? = null

    //A reference to the opened [CameraDevice].
    private var mCameraDevice: CameraDevice? = null

    //The [Size] of camera preview.
    private var mPreviewSize: Size? = null

    //[CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release()
            mCameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

    }

    //An additional thread for running tasks that shouldn't block the UI.
    private var mBackgroundThread: HandlerThread? = null

    //A [Handler] for running tasks in the background.
    private var mBackgroundHandler: Handler? = null

    //An [ImageReader] that handles still image capture.
    private var mImageReader: ImageReader? = null

    //This is the output file for our picture.
    private var mFile: File? = null

    //This a callback object for the [ImageReader]. "onImageAvailable" will be called when a still image is ready to be saved.
    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        mBackgroundHandler!!.post(
            ImageSaver(
                reader.acquireNextImage(),
                mFile!!
            )
        )
    }

    //[CaptureRequest.Builder] for the camera preview
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null

    //[CaptureRequest] generated by [.mPreviewRequestBuilder]
    private var mPreviewRequest: CaptureRequest? = null

    //The current state of camera state for taking pictures.
    private var mState = STATE_PREVIEW

    //A [Semaphore] to prevent the app from exiting before closing the camera.
    private val mCameraOpenCloseLock = Semaphore(1)

    //Whether the current camera device supports Flash or not.
    private var mFlashSupported: Boolean = false

    //Orientation of the camera sensor
    private var mSensorOrientation: Int = 0

    //A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> {
                }// We have nothing to do when the camera preview is working normally.
                STATE_WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else {
                            runPreCaptureSequence()
                        }
                    }
                }
                STATE_WAITING_PRE_CAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        mState = STATE_WAITING_NON_PRE_CAPTURE
                    }
                }
                STATE_WAITING_NON_PRE_CAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }

    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "onCreate")
            val layoutParams: WindowManager.LayoutParams =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    )
                } else {
                    WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    )
                }
            windowManager =
                this@SilentPictureService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            mTextureView = MyTextureView(this, null, 0)

            layoutParams.gravity = Gravity.START or Gravity.TOP
            windowManager!!.addView(mTextureView, layoutParams)
            mTextureView!!.surfaceTextureListener = this
            startBackgroundThread()

        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    override fun onStart(intent: Intent?, startId: Int) {
        Log.i(TAG, "onStart")
        super.onStart(intent, startId)
        startBackgroundThread()
    }

    override fun onHandleIntent(intent: Intent?) {
        Log.i(TAG, "onHandleIntent")
        while (!isDestroy) {
            try {
                if (alreadyOpen) {
                    val name = Date().time.toString() + ".jpg"
                    val dir =
                        File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!.toString() + "/picture")
                    if (!dir.exists()) {
                        dir.mkdir()
                    }
                    mFile = File(dir.absolutePath, name)
                    takePicture()
                }
                Thread.sleep(capturePeriod.toLong())

            } catch (e: InterruptedException) {
                Log.i(TAG, e.toString())
            }

        }

    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        isDestroy = true
        closeCamera()
        stopBackgroundThread()
        windowManager!!.removeView(mTextureView)
        stopForeground(true)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceTextureAvailable")
        openCamera(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceTextureSizeChanged")
        configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.i(TAG, "onSurfaceTextureDestroyed")
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        Log.i(TAG, "onSurfaceTextureUpdated")
    }


    /*
       Sets up member variables related to camera.
       @param width  The width of available size for camera preview
       @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // 使用定义的摄像头
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing != mFacing) {
                    continue
                }

                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                    mutableListOf(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea()
                )

                //设置图片的大小，格式
                mImageReader =
                    ImageReader.newInstance(pictureWidth, pictureHeight, ImageFormat.JPEG, 2)
                mImageReader!!.setOnImageAvailableListener(
                    mOnImageAvailableListener, mBackgroundHandler
                )

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = windowManager!!.defaultDisplay.rotation

                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                var swappedDimensions = false
                when (displayRotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                        swappedDimensions = true
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                        swappedDimensions = true
                    }
                    else -> Log.e(TAG, "Display rotation is invalid: $displayRotation")
                }

                val displaySize = Point()
                windowManager!!.defaultDisplay.getSize(displaySize)
                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width
                    maxPreviewWidth = displaySize.y
                    maxPreviewHeight = displaySize.x
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }


                mPreviewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                    maxPreviewHeight, largest
                )

                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView!!.setAspectRatio(
                        mPreviewSize!!.width, mPreviewSize!!.height
                    )
                } else {
                    mTextureView!!.setAspectRatio(
                        mPreviewSize!!.height, mPreviewSize!!.width
                    )
                }

                // Check if the flash is supported.
                val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                mFlashSupported = available ?: false

                mCameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
        }

    }

    //Opens the camera specified by .
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(mCameraId!!, mStateCallback, mBackgroundHandler)
            alreadyOpen = true
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }

    }

    //Closes the current [CameraDevice].
    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            if (null != mCaptureSession) {
                mCaptureSession!!.close()
                mCaptureSession = null
            }
            if (null != mCameraDevice) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }
            if (null != mImageReader) {
                mImageReader!!.close()
                mImageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
        }
        alreadyOpen = false
    }

    //Starts a background thread and its [Handler].
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    // Stops the background thread and its [Handler].
    private fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            Log.i(TAG, e.toString())
        }

    }

    //Creates a new [CameraCaptureSession] for camera preview.
    private fun createCameraPreviewSession() {
        try {
            val texture = mTextureView!!.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder!!.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice!!.createCaptureSession(
                mutableListOf(surface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == mCameraDevice) {
                            return
                        }

                        // When the session is ready, we start displaying the preview.
                        mCaptureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            mPreviewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            setAutoFlash(mPreviewRequestBuilder)

                            // Finally, we start displaying the camera preview.
                            mPreviewRequest = mPreviewRequestBuilder!!.build()
                            mCaptureSession!!.setRepeatingRequest(
                                mPreviewRequest!!,
                                mCaptureCallback, mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.i(TAG, e.toString())
                        }

                    }

                    override fun onConfigureFailed(
                        cameraCaptureSession: CameraCaptureSession
                    ) {
                        Log.i(TAG, "Failed")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        }

    }

    /*
      Configures the necessary [Matrix] transformation to `mTextureView`.
      This method should be called after the camera preview size is determined in
      setUpCameraOutputs and also the size of `mTextureView` is fixed.

       @param viewWidth  The width of `mTextureView`
       @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == mTextureView || null == mPreviewSize) {
            return
        }
        val rotation = windowManager!!.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / mPreviewSize!!.height,
                viewWidth.toFloat() / mPreviewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        mTextureView!!.setTransform(matrix)
    }

    //Initiate a still image capture.
    private fun takePicture() {
        lockFocus()
    }

    //Lock the focus as the first step for a still image capture.
    private fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK
            if (mCaptureSession != null) {
                mCaptureSession!!.capture(
                    mPreviewRequestBuilder!!.build(), mCaptureCallback,
                    mBackgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        }

    }

    /*
       Run the precapture sequence for capturing a still image. This method should be called when
       we get a response in [.mCaptureCallback] from [.lockFocus].
     */
    private fun runPreCaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            mState = STATE_WAITING_PRE_CAPTURE
            mCaptureSession!!.capture(
                mPreviewRequestBuilder!!.build(), mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        }

    }

    /*
      Capture a still picture. This method should be called when we get a response in
      [.mCaptureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        try {
            if (null == mCameraDevice) {
                return
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(mImageReader!!.surface)

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            setAutoFlash(captureBuilder)

            // Orientation
            val rotation = windowManager!!.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.i(TAG, "Saved: " + mFile!!)
                    unlockFocus()
                }
            }

            mCaptureSession!!.stopRepeating()
            mCaptureSession!!.abortCaptures()
            mCaptureSession!!.capture(captureBuilder.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        }

    }

    /*
     Retrieves the JPEG orientation from the specified screen rotation.
     @param rotation The screen rotation.
     @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private fun getOrientation(rotation: Int): Int {
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360
    }

    //Unlock the focus. This method should be called when still image capture sequence is finished.
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            setAutoFlash(mPreviewRequestBuilder)
            mCaptureSession!!.capture(
                mPreviewRequestBuilder!!.build(), mCaptureCallback,
                mBackgroundHandler
            )
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW
            mCaptureSession!!.setRepeatingRequest(
                mPreviewRequest!!, mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.i(TAG, e.toString())
        }

    }


    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder?) {
        if (mFlashSupported) {
//            requestBuilder!!.set(
//                CaptureRequest.CONTROL_AE_MODE,
//                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
//            )
        }
    }

    //saves a JPEG [Image] into the specified [File].
    private class ImageSaver internal constructor(
        //The JPEG image
        private val mImage: Image,
        //The file we save the image into.
        private val mFile: File
    ) : Runnable {

        override fun run() {
            val buffer = mImage.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var output: FileOutputStream? = null
            try {
                output = FileOutputStream(mFile)
                output.write(bytes)
            } catch (e: IOException) {
                Log.i(TAG, e.toString())
            } finally {
                mImage.close()
                if (null != output) {
                    try {
                        output.close()
                    } catch (e: IOException) {
                        Log.i(TAG, e.toString())
                    }

                }
            }
        }

    }

    //Compares two `Size`s based on their areas.
    internal class CompareSizesByArea : Comparator<Size> {

        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        return super.onStartCommand(intent, flags, startId)
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val mNotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "silentCapture_01"
        val channelName = "后台拍照" // 通知渠道的名字.
        val channelDescription = "测试后台拍照"// 通知渠道的描述
        val channelImportance =
            NotificationManager.IMPORTANCE_NONE//Tip：如果想偷拍就选择这个，静音状态,然后设置APP不显示通知。
        val mChannel = NotificationChannel(channelId, channelName, channelImportance)
        mChannel.description = channelDescription
        mChannel.enableLights(false)
        mChannel.lightColor = Color.RED
        mChannel.enableVibration(false)
        mChannel.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
        mNotificationManager.createNotificationChannel(mChannel)
        val notifyID = 1
        val notification = Notification.Builder(this)
//            .setContentTitle("SilentCapture")
//            .setContentText("U are very handsome!")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setChannelId(channelId)
            .build()
        startForeground(notifyID, notification)
    }



}
