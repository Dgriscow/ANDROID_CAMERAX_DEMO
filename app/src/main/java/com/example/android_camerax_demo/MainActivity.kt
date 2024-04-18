package com.example.android_camerax_demo

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.Image
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.android_camerax_demo.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors



typealias LumaListener = (luma: Double) -> Unit



class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        //Request Permission from camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val nameTimeStamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nameTimeStamp)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg") //use JPEG as the file format
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                //Pictures is the name of the folder storing the image, ANDROID DEFAULT IS NOW PICTURES
                //image is being put inside of pictures INSIDE another folder called `CameraX-Image`
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                //On error, handle the error as desired
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Saved Image!   ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

            }
        )






    }

    private fun captureVideo() {
        //if video capture available, usse it, if not return
        val videoCapture = this.videoCapture ?:return

        viewBinding.videoCaptureButton.isEnabled = false
        //create a new `currRecording` from the recording itself
        val currRecording = recording

        if (currRecording != null){
            //Stop the Current Recording Session
            currRecording.stop()
            recording = null
            return
        }

        //Create and start a new recording session on capture video

        //Create name time stampe
        val nameTimeStamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        //Create the comment to store
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nameTimeStamp)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4") //Setting the video type for the video

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED){
                    //if audio is granted, record audio
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {

                    //On Video Start, Do the Following
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    //---------Bottom Of is Video Recording

                    //On Record Event Finalize (video is over or has error
                    is VideoRecordEvent.Finalize -> {

                        //Check if there was no error at the end
                        if (!recordEvent.hasError()){
                            //Video ended in success, make the message
                            val msg = "Video Saved! " +
                                    "${recordEvent.outputResults.outputUri}"

                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        }else {
                            //Recording ENDED in ERROR
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video Ended In a Error!" + "${recordEvent.error}")
                        }

                        //View binding to the video capture button
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }


                    }





                }

            }



    }
    //This function starts the camera, preview, and image analysis for the camera app to use
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        //Add a listener to listen to the constant state of the camera
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            //begin building the preview for the camera to show off
            val preview = Preview.Builder()
                .build()
                .also {
                    //set where the preview is going to be displayed to, which is the preview from activity mains XML
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            //Instantiate the image capture class, and build it
            imageCapture = ImageCapture.Builder()
                .build() //building from the method



//            //Instantiate the image analyzer for slight image correction
//            val imageAnalyzer = ImageAnalysis.Builder()
//                .build()
//                .also {
//                    //on the image analysis, correct ITS average luminocity
//                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
//                        Log.d(TAG, "Average Luminosity: $luma")
//                    })
//                }
//
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try{

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)

            }catch (exc:Exception){
                Log.e(TAG, "Use case binding failed", exc)
            }



        },
            ContextCompat.getMainExecutor(this) //get the main exEcutor for of this context and apply it
        )

    }

    private fun requestPermissions() {
        //Function handling permission requests.
        //NTS: THIS IS HOW IT SHOULD BE DONE IN THE FUTURE, WITH ADDITIONS AND CATCHES ADDED ON!
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }



    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                //LIST OF PERMISSIONS THE APP NEEDS TO ASK FOR
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }//bottom of companion object


    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->

            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {

                //This is the iteration process through all the permissions the APP needs to ask for
                //when it noticies ONE is not granted, we assume all are not and show the user they cannot proceed
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                //when the permission granted is false, display a toast message confirming they need to accept all
                Toast.makeText(baseContext,
                    "Permission request denied, please accept ALL permissions",
                    Toast.LENGTH_SHORT).show()
            } else {
                //when the user accepts ALL permissions, load start the camera
                startCamera() //start camera immedietly begins displaying to the preview
            }
        }




    //Create a PRIVATE CLASS just for the activity to use for camera imageAnalysis
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        //Overiding the byte buffer (buffer contains the image processing data)

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }





}