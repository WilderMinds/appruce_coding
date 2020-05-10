package com.expresspay.cameraapp.ui.main;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;

import com.expresspay.cameraapp.R;
import com.expresspay.cameraapp.network.APIService;
import com.expresspay.cameraapp.network.RetrofitClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

public class MainFragment extends Fragment {

    private static String TAG = MainFragment.class.getCanonicalName();

    private MainViewModel mViewModel;

    // camera
    private CameraManager cameraManager;
    private int cameraFacing;
    private TextureView.SurfaceTextureListener surfaceTextureListener;
    private CameraDevice.StateCallback stateCallback;
    private Size previewSize;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;

    // async
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    // widgets
    private TextureView textureView;
    private Button captureBtn, cancelBtn, uploadBtn;
    private ProgressBar uploadSpinner;

    // storage
    private File appImagesDirectory, imageFile;

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initImageStorageDirectory();
    }

    private void initImageStorageDirectory() {
        File storageDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        appImagesDirectory = new File(storageDirectory, getResources().getString(R.string.app_name));
        if (!appImagesDirectory.exists()) {
            boolean created = appImagesDirectory.mkdirs();
            if (!created) {
                Log.e(TAG, "Failed to create directory");
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.main_fragment, container, false);
        textureView = view.findViewById(R.id.camera_view);
        captureBtn = view.findViewById(R.id.capture_btn);
        cancelBtn = view.findViewById(R.id.cancel_btn);
        uploadBtn = view.findViewById(R.id.upload_btn);
        uploadSpinner = view.findViewById(R.id.upload_progress);
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewModel = ViewModelProviders.of(this).get(MainViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        toggleVisibility(false);

        // camera
        cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        cameraFacing = CameraCharacteristics.LENS_FACING_BACK;


        // textureview listener
        surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                initCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        };
        // textureView.setSurfaceTextureListener(surfaceTextureListener);


        // camera state callback
        stateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                createCameraPreview();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
                if (cameraDevice != null){
                    cameraDevice.close();
                }
                cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int i) {
                camera.close();
                if (cameraDevice != null){
                    cameraDevice.close();
                }
                cameraDevice = null;
                Log.e(TAG, "camera callback error => " + i);
            }
        };


        // captureBtn
        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                capture();
            }
        });


        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });

        uploadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadSpinner.setVisibility(View.VISIBLE);
                uploadImage();
            }
        });
    }

    private void initCamera() {
        try {

            // get and setup camera
            cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
            String[] availableCameraList = cameraManager.getCameraIdList();
            Log.e(TAG, "camera id count => " + availableCameraList.length);


            for (String _cameraId : availableCameraList) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(_cameraId);

                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == cameraFacing) {
                    StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    previewSize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
                    cameraId = _cameraId;
                    Log.e(TAG, "camera cameraId set");
                    break;
                }
            }


            // open camera
            if (ContextCompat.checkSelfPermission(getActivity() ,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "opening camera");
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            } else {
                throw new Exception("Camera Permission denied");
            }


        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private void createCameraPreview(){
        try{
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession captureSession) {

                    // check if camera is already closed
                    if (cameraDevice == null){
                        return;
                    }

                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        cameraCaptureSession = captureSession;
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    }catch (Exception e){
                        Log.e(TAG, "failed to create session");
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession captureSession) {
                    cameraCaptureSession = captureSession;

                }
            }, backgroundHandler);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void capture(){

        FileOutputStream photo = null;
        try {
            imageFile = createImageFile();
            photo = new FileOutputStream(imageFile);
            textureView.getBitmap().compress(Bitmap.CompressFormat.PNG, 100, photo);

            freeze();

        } catch (Exception e){
            e.printStackTrace();
        } finally {
//            unfreeze();

            if (photo != null){
                try {
                    // close stream
                    photo.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void uploadImage() {
        Retrofit retrofit = RetrofitClient.getRetrofitClient(getActivity());

        APIService apiService = retrofit.create(APIService.class);

        //Create a file object using file path
        File file = imageFile;//new File(filePath);

        // Create a request body with file and image media type
        RequestBody fileReqBody = RequestBody.create(MediaType.parse("image/*"), file);

        // Create MultipartBody.Part using file request-body,file name and part name
        MultipartBody.Part part = MultipartBody.Part.createFormData("upload", file.getName(), fileReqBody);

        //generate random string
        String random = random();
        //Create request body with text description and text media type
        RequestBody userId = RequestBody.create(MultipartBody.FORM, random);

        //
        Call call = apiService.uploadImage(part, userId);

        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                Log.e(TAG, response.toString());
                unfreeze();
            }

            @Override
            public void onFailure(Call call, Throwable t) {
                t.printStackTrace();
                unfreeze();
            }
        });
    }

    public static String random() {
        Random generator = new Random();
        StringBuilder randomStringBuilder = new StringBuilder();
        int randomLength = generator.nextInt(12);
        char tempChar;
        for (int i = 0; i < randomLength; i++){
            tempChar = (char) (generator.nextInt(96) + 32);
            randomStringBuilder.append(tempChar);
        }
        return randomStringBuilder.toString();
    }

    private void freeze() {
        // pause preview to see taken photo
        try {
            cameraCaptureSession.capture(captureRequestBuilder.build(),
                    null, backgroundHandler);

            // upload image
            toggleVisibility(true);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void unfreeze() {
        // resume preview after image has been uploaded
        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),
                    null, backgroundHandler);


            toggleVisibility(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void toggleVisibility(boolean isPreviewLocked){
        if (isPreviewLocked){
            captureBtn.setVisibility(View.GONE);
            cancelBtn.setVisibility(View.VISIBLE);
            uploadBtn.setVisibility(View.VISIBLE);
        } else {
            captureBtn.setVisibility(View.VISIBLE);
            cancelBtn.setVisibility(View.GONE);
            uploadBtn.setVisibility(View.GONE);
            uploadSpinner.setVisibility(View.GONE);
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "image_" + timeStamp + "_";
        return new File(Environment.getExternalStorageDirectory()+"/"+ imageFileName+"pic.jpg");
        //return File.createTempFile(imageFileName, ".jpg", appImagesDirectory);
    }

    @Override
    public void onResume() {
        super.onResume();

        openBackgroundThread();
        if (textureView.isAvailable()){
            initCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        closeCamera();
        closeBackgroundThread();
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void openBackgroundThread() {
        backgroundThread = new HandlerThread("camera_background_thread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void closeBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
    }
}
