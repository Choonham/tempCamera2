package com.example.samplecamera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import com.yanzhenjie.permission.Action;
import com.yanzhenjie.permission.AndPermission;
import com.yanzhenjie.permission.runtime.Permission;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextureView textureView;
    private Button button;
    //화면 각도 상수
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    // 카메라 default 각도 변경 (가로 -> 세로)
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    // Camera2 Variables
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureReqBuilder;

    // variables need to save image
    private Size imageDimensions;
    private File file;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.textureView);
        button = findViewById(R.id.button);

        AndPermission.with(this)
                .runtime()
                .permission(
                        Permission.CAMERA,
                        Permission.WRITE_EXTERNAL_STORAGE,
                        Permission.READ_EXTERNAL_STORAGE)
                .onGranted(new Action<List<String>>() {
                    @Override
                    public void onAction(List<String> permissions) {
                        startCamera();
                    }
                })
                .onDenied(new Action<List<String>>() {
                    @Override
                    public void onAction(List<String> permissions) {

                    }
                })
                .start();

    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    // TextureView 에 Listener 지정
    private void startCamera() {
        textureView.setSurfaceTextureListener(textureListener);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try{
                    takePicture();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // textureListener 가 참조되었때 콜백 함수가 실행할 메서드
    private void openCamera() throws CameraAccessException {
        // getSystemService() 를 사용하여 CameraManager 객체 참조
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        /**
         *      Manager 객체를 이용하여 카메라 id 리스트를 얻어 온다.
         *      일반적으로 0번 : 후면 카메라, 1번 : 전면 카메라이다.
         *      얻은 cameraId를 바탕으로 해당 카메라의 정보를 가지는 CameraCharacteristics 객체를 반환하며,
         *      characteristics.get(characteristics.LENS_FACING) 으로 카메라 종류별 상수 값을 얻을 수 있다.
         *
         *      LENS_FACING_FRONT: 전면 카메라. value : 0
         *      LENS_FACING_BACK: 후면 카메라. value : 1
         *     LENS_FACING_EXTERNAL: 기타 카메라. value : 2
         * */
        cameraId = manager.getCameraIdList()[0];
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // StreamConfigurationMap 객체는 각종 지원 정보가 포함되어 있다.
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // 이미지 사이즈 반환
        imageDimensions = map.getOutputSizes(SurfaceTexture.class)[0];

        // Manager 가 Camera 를 open 할 때는 반드시 권한을 확인해야 한다.
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AndPermission.with(this)
                    .runtime()
                    .permission(
                            Permission.CAMERA,
                            Permission.WRITE_EXTERNAL_STORAGE,
                            Permission.READ_EXTERNAL_STORAGE
                    )
                    .onGranted(new Action<List<String>>() {
                        @Override
                        public void onAction(List<String> permissions) {
                            startCamera();
                        }
                    })
                    .start();
            return;
        }
        // CameraID 와 Callback 객체를 지정한 뒤, 해당 카메라를 오픈
        manager.openCamera(cameraId, stateCallback, null);
    }

    // 파일 스트림 저장
    private void save(byte[] bytes) throws IOException {
        OutputStream outputStream = null;
        outputStream = new FileOutputStream(file);
        outputStream.write(bytes);
        outputStream.close();
    }

    protected void stopBackgroundThread() throws InterruptedException {
        mBackgroundThread.quitSafely();
        mBackgroundThread.join();
        mBackgroundThread = null;
        mBackgroundHandler = null;
    }

    private void takePicture() throws CameraAccessException {
        if(cameraDevice == null) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
        Size[] jpegSizes = null;

        jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);

        int width = 640;
        int height = 480;

        if(jpegSizes != null && jpegSizes.length > 0) {
            width = jpegSizes[0].getWidth();
            height = jpegSizes[0].getHeight();
        }

        ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
        List<Surface> outputSurfaces = new ArrayList<>(2);
        outputSurfaces.add(reader.getSurface());

        outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));

        final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(reader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

        Long tsLong  = System.currentTimeMillis()/1000;
        String ts = tsLong.toString();

        file = new File(Environment.getExternalStorageDirectory()+"/DCIM", "pic.jpg");

        ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Image image = null;

                image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);

                try{
                    save(bytes);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if(image != null) {
                        image.close();
                        reader.close();
                    }
                }

            }
        };

        reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

        final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                try{
                    createCameraPreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        };

        cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                try {
                    session.capture(captureBuilder.build(), captureCallback, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

            }
        }, mBackgroundHandler);

    }


    // 리스너 콜백 함수
    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        // 이 리스너가 TextureView에 리스너로 지정되었을 때 실행될 콜백 함수
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };


    // manager.openCamera() 메서드가 사용할 콜백
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //open 메서드가 받는 CameraDevice 인자를 맴버 인스턴스에 참조
            cameraDevice = camera;
            try {
                // TextureView 에 화면을 표시할 메서드
                createCameraPreview();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    // stateCallback 객체의 open 함수가 호출하는 메서드
    private void createCameraPreview() throws CameraAccessException {
        // TextureView 로 사용할 SurfaceTexture 객체을 참조한다.
        SurfaceTexture texture = textureView.getSurfaceTexture();

        // SurfaceTexture 객체의 버퍼 사이즈를 imageDimensions 로 초기화
        texture.setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());

        // 해당 SurfaceTexture 객체를 Surface 객체로 감싸준다.
        Surface surface = new Surface(texture);

        // CameraDevice 객체에서 리퀘스트 빌더를 가져오고 Template 을 넣어준다.
        captureReqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        // 이미지를 넣어줄 타겟으로 surface 를 넣어준다.
        captureReqBuilder.addTarget(surface);

        /**
         *   카메라는 실시간으로 동작하는 기능이다.
         *   따로 실시간 동작하는 카메라 쓰레드에서 이미지를 받아올텐데, 그러한 흐림이 바로 세션이다.
         *   세선에 리퀘스트를 넣어주면 해당 리퀘스트의 기능을 수행하도록 할 수 있다.
         *
         *   인자 값으로, 이미지를 출력해줄 surface 넣고, 세션을 생성할 때  실행될 콜백을 넣어준다.
         * */
        cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if(cameraDevice == null) {
                    return;
                }
                cameraCaptureSession = session;

                try{
                    updatePreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                Toast.makeText(getApplicationContext(), "Configuration Changed", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    // 카메라가 실행 준비가 되었을 때, onConfigured() 콜백 메서드가 실행되는데, 이 때 호출할 메서드이다.
    private void updatePreview() throws CameraAccessException {
        if(cameraDevice == null) {
            return;
        }

        // 리퀘스트 세팅: 빌드 전 카메라 기능에 대한 세팅
        captureReqBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        // 세션 세팅
        cameraCaptureSession.setRepeatingRequest(captureReqBuilder.build(), null, mBackgroundHandler);
    }


    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (textureView.isAvailable()) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            stopBackgroundThread();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}