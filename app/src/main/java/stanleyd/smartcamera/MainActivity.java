package stanleyd.smartcamera;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private String[] cameraPermissions = {Manifest.permission.CAMERA};

    private static final int CAMERA_REQUEST_CODE = 200;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private Size mPreviewSize;
    private File photoFile;
    private CameraCaptureSession cameraCaptureSession;
    private Surface surface;

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }


    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            // Камера открыта, можно запускать предварительный просмотр
            cameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice = null;
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {

            cameraDevice = null;
            camera.close();
        }
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mPreviewSize = map.getOutputSizes(SurfaceTexture.class)[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Поверхность изменена, например, из-за поворота экрана
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // Обновление TextureView. Некоторый код здесь, если необходимо.
        }
    };

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ImageButton makePhotoButton = (ImageButton) findViewById(R.id.makePhotoButton);
        checkCameraPermissions();

        SharedPreferences defaultSettings = getSharedPreferences("settings", MODE_PRIVATE);
        if(!defaultSettings.contains("FirstStart")){
            SharedPreferences.Editor editor;
            editor = defaultSettings.edit();
            editor.putString("FirstStart","true");
            editor.putInt("FileName",1);
            editor.apply();
        }

        textureView = findViewById(R.id.texture_view);
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, CAMERA_REQUEST_CODE);
        }

        Button saveButton = findViewById(R.id.saveButton);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                saveImage2();
                Intent intent = getIntent();
                finish();
                startActivity(intent);
            }
        });

        Button cancelButton = findViewById(R.id.cancelButton);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = getIntent();
                finish();
                startActivity(intent);
            }
        });


        makePhotoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                photoFile = createImageFile(); // создаем файл для фото
                if (photoFile != null) {
                    takePicture();
                    visability();
                }
            }
        });

    }

    private void visability(){
        ImageButton makePhotoButton = (ImageButton) findViewById(R.id.makePhotoButton);
        Button saveButton = findViewById(R.id.saveButton);
        Button cancelButton = findViewById(R.id.cancelButton);
        if(makePhotoButton.getVisibility()==View.VISIBLE){
            saveButton.setVisibility(View.VISIBLE);
            cancelButton.setVisibility(View.VISIBLE);
            makePhotoButton.setVisibility(View.INVISIBLE);
        } else {
            saveButton.setVisibility(View.INVISIBLE);
            cancelButton.setVisibility(View.INVISIBLE);
            makePhotoButton.setVisibility(View.VISIBLE);
        }

    }

    private void saveImage2() {
        // Получение изображения из ImageView
        ImageView imageView = findViewById(R.id.previewImage);
        Drawable drawable = imageView.getDrawable();
        Bitmap bitmap;

        // Проверка, является ли Drawable BitmapDrawable
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            // Если Drawable не является BitmapDrawable, создайте новый Bitmap
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }

        File directory;
        // Получение пути к директории для сохранения файла
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Устройство работает на Android 10 (API уровень 29) и выше
            File[] mediaDirs = getExternalMediaDirs();
            directory = mediaDirs[0];
        } else {
            // Устройство работает на Android версии ниже 10
            directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }

        File imagePath = new File(directory, "myImages");

        // Проверка, существует ли директория, и создание ее, если она не существует
        if (!imagePath.exists()) {
            imagePath.mkdirs();
        }

        // Генерация имени файла
        SharedPreferences defaultSettings = getSharedPreferences("settings", MODE_PRIVATE);
        String nameDigit = String.valueOf(defaultSettings.getInt("FileName",0));
        String fileName = "image"+nameDigit+".png";
        File outputFile = new File(imagePath, fileName);

        try {
            // Поворот изображения на 90 градусов по часовой стрелке
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            // Сохранение повернутого изображения
            FileOutputStream fos = new FileOutputStream(outputFile);
            rotatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Обновление галереи, чтобы новое изображение было видимым
        MediaScannerConnection.scanFile(this, new String[]{outputFile.getAbsolutePath()}, null, null);

        // Вывод сообщения об успешном сохранении
        Toast.makeText(this, "Изображение сохранено", Toast.LENGTH_SHORT).show();
    }


    private String getFileExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf(".");
        if (extensionIndex != -1) {
            return fileName.substring(extensionIndex + 1);
        }
        return "";
    }

    private String getFileNameWithoutExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf(".");
        if (extensionIndex != -1) {
            return fileName.substring(0, extensionIndex);
        }
        return fileName;
    }

    private File createImageFile() {
        // Создайте имя файла изображения
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = null;
        try {
            image = File.createTempFile(
                    imageFileName,  /* префикс */
                    ".jpg",         /* суффикс */
                    storageDir      /* каталог */
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
        return image;
    }



    private void startPreview() {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        surface = new Surface(surfaceTexture); // Присваиваем surface полю класса

        try {
            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // Обработка ошибки конфигурации
                    Log.e(TAG, "Camera capture session configuration failed");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        try {
            int width = mPreviewSize.getWidth();
            int height = mPreviewSize.getHeight();

            // Создание ImageReader с добавлением Surface
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            saveAndDisplayImage(bytes, photoFile);
                        }
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            }, null);

            List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(surface);

            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.addTarget(surface);
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Capture session configuration failed");
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    super.onClosed(session);
                    // Освобождение буферов SurfaceTexture
                    reader.close();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    private void saveAndDisplayImage(byte[] bytes, File photoFile) {
        ImageView previewImage = findViewById(R.id.previewImage);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        previewImage.setImageBitmap(bitmap);

        previewImage.setVisibility(View.VISIBLE);
    }

    private void checkCameraPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, cameraPermissions, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Intent intent = getIntent();
                finish();
                startActivity(intent);
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Нужно разрешение", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
