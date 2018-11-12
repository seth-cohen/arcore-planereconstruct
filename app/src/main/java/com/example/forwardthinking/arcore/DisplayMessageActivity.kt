package com.example.forwardthinking.arcore

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_display_message.*
import com.example.forwardthinking.helpers.*
import com.example.forwardthinking.renderers.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DisplayMessageActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView

    private var userRequestedInstall: Boolean = false

    private var arSession: Session? = null
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private lateinit var tapHelper: TapHelper

    private val backgroundRenderer = BackgroundRenderer()
    private val virtualObject = ObjectRenderer()
    private val virtualObjectShadow = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    private class ColoredAnchor(a: Anchor, color4f: FloatArray) {
        var anchor: Anchor = a
        var color: FloatArray = color4f
    }

    private var anchors: ArrayList<ColoredAnchor> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_message)

        val message = intent.getStringExtra(EXTRA_MESSAGE)
        textView.text = message

        surfaceView = glSurfaceView
        displayRotationHelper = DisplayRotationHelper(this)

        // Set up the tap listener
        tapHelper = TapHelper(this)
        surfaceView.setOnTouchListener(tapHelper)

        // Set up renderer.
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        // Alpha used for plane blending.
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        userRequestedInstall = false
    }

    // Ensure that we have camera permission
    override fun onResume() {
        super.onResume()

        // ARCore requires camera permission to operate
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        // Make sure ARCore is installed and up to date.
        try {
            if (arSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        arSession = Session(this)
                        Toast.makeText(this, "ARCore Installed", Toast.LENGTH_LONG).show()
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        // Ensures next invocation of requestInstall() will either return
                        // INSTALLED or throw an exception.
                        userRequestedInstall = false
                        Toast.makeText(this, "Installing ARCore", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(this, "Issue installing ARCore", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "TODO: handle exception $e", Toast.LENGTH_LONG)
                .show()
            return
        } catch (e: Exception) {  // Current catch statements.
            return  // mSession is still null.
        }

        try {
            arSession?.resume()
        } catch (e: CameraNotAvailableException) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.")
            arSession = null
            return
        }

        surfaceView.onResume()
        displayRotationHelper!!.onResume()

        messageSnackbarHelper.showMessage(this, "Searching for surfaces...")
    }

    override fun onPause() {
        super.onPause()
        arSession?.let {
            displayRotationHelper!!.onPause()
            surfaceView.onPause()
            it.pause()
        }
    }

    // Necessary to ensure that camera permission was actually granted else
    // ask again
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to properly run this application", Toast.LENGTH_LONG)
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again"
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

     override fun onDrawFrame(gl: GL10?) {
         // Clear screen to notify driver it should not load any pixels from previous frame.
         GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

         if (arSession == null) {
             return
         }
         // Notify ARCore session that the view size changed so that the perspective matrix and
         // the video background can be properly adjusted.
         displayRotationHelper!!.updateSessionIfNeeded(arSession!!)

         try {
             arSession!!.setCameraTextureName(backgroundRenderer.textureId)

             // Obtain the current frame from ARSession. When the configuration is set to
             // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
             // camera framerate.
             val frame = arSession!!.update()
             val camera = frame.camera

             // Handle taps. Handling only one tap per frame, as taps are usually low frequency
             // compared to frame rate.
             val tap = tapHelper.poll()
             if (tap != null && camera.trackingState == TrackingState.TRACKING) {
                 for (hit in frame.hitTest(tap)) {
                     // Check if any plane was hit, and if it was hit inside the plane polygon
                     val trackable = hit.trackable
                     // Creates an anchor if a plane or an oriented point was hit.
                     if ((trackable is Plane
                                 && trackable.isPoseInPolygon(hit.hitPose)
                                 && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                         || trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                         // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                         // Cap the number of objects created. This avoids overloading both the
                         // rendering system and ARCore.
                         if (anchors.size >= 20) {
                             anchors[0].anchor.detach()
                             anchors.removeAt(0)
                         }
                         // Adding an Anchor tells ARCore that it should track this position in
                         // space. This anchor is created on the Plane to place the 3D model
                         // in the correct position relative both to the world and to the plane.
                         anchors.add(ColoredAnchor(hit.createAnchor(), floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)))
                         break
                     }
                 }
             }

             // Draw background.
             backgroundRenderer.draw(frame)

             // If not tracking, don't draw 3d objects.
             if (camera.trackingState == TrackingState.PAUSED) {
                 return
             }

             // Get projection matrix.
             val projmtx = FloatArray(16)
             camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

             // Get camera matrix and draw.
             val viewmtx = FloatArray(16)
             camera.getViewMatrix(viewmtx, 0)

             // Compute lighting from average intensity of the image.
             // The first three components are color scaling factors.
             // The last one is the average pixel intensity in gamma space.
             val colorCorrectionRgba = FloatArray(4)
             frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

             // Visualize tracked points.
             val pointCloud = frame.acquirePointCloud()
             pointCloudRenderer.update(pointCloud)
             pointCloudRenderer.draw(viewmtx, projmtx)

             val points = pointCloud.points
             logText.append("X: ${points.get()}, Y: ${points.get()}, Z: ${points.get()}, conf: ${points.get()}")

             // Application is responsible for releasing the point cloud resources after
             // using it.
             pointCloud.release()

             // Check if we detected at least one plane. If so, hide the loading message.
             if (messageSnackbarHelper.isShowing) {
                 for (plane in arSession!!.getAllTrackables(Plane::class.java)) {
                     if (plane.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING
                         && plane.trackingState == TrackingState.TRACKING) {
                         messageSnackbarHelper.hide(this)
                         break
                     }
                 }
             }

             // Visualize planes.
             planeRenderer.drawPlanes(
                 arSession!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx)

             // Visualize anchors created by touch.
             val scaleFactor = 1.0f
             for (anchor in anchors) {
                 if (anchor.anchor.trackingState != TrackingState.TRACKING) {
                     continue
                 }
                 // Get the current pose of an Anchor in world space. The Anchor pose is updated
                 // during calls to session.update() as ARCore refines its estimate of the world.
                 anchor.anchor.pose.toMatrix(anchorMatrix, 0)

                 // Update and draw the model and its shadow.
                 virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                 virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                 virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba)
                 virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba)
             }

         } catch (t: Throwable) {
             // Avoid crashing the application due to unhandled exceptions.
             Log.e(TAG, "Exception on the OpenGL thread", t)
         }

     }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f,0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(/*context=*/this)

            virtualObject.createOnGlThread(/*context=*/this, "models/andy.obj",
                "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)

            virtualObjectShadow.createOnGlThread(
                /*context=*/ this, "models/andy_shadow.obj",
                "models/andy_shadow.png")
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read asset file", e)
        }
    }

    companion object {
        private val TAG = DisplayMessageActivity::class.java.simpleName
    }
}
