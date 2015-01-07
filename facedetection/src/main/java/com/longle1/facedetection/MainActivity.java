/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Long Le
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.longle1.facedetection;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.loopj.android.http.RequestParams;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.opencv_objdetect;
import org.bytedeco.javacpp.swresample;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.bytedeco.javacpp.opencv_core.CvMemStorage;
import static org.bytedeco.javacpp.opencv_core.CvRect;
import static org.bytedeco.javacpp.opencv_core.CvSeq;
import static org.bytedeco.javacpp.opencv_core.IPL_DEPTH_8U;
import static org.bytedeco.javacpp.opencv_core.IplImage;
import static org.bytedeco.javacpp.opencv_core.cvClearMemStorage;
import static org.bytedeco.javacpp.opencv_core.cvGetSeqElem;
import static org.bytedeco.javacpp.opencv_core.cvLoad;
import static org.bytedeco.javacpp.opencv_objdetect.CV_HAAR_DO_CANNY_PRUNING;
import static org.bytedeco.javacpp.opencv_objdetect.CvHaarClassifierCascade;
import static org.bytedeco.javacpp.opencv_objdetect.cvHaarDetectObjects;

// ----------------------------------------------------------------------

public class MainActivity extends Activity {
    private FrameLayout layout;
    private FaceView mFaceView;
    private CameraPreview mCameraPreview;
    static boolean faceState = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create our Preview view and set it as the content of our activity.
        try {
            layout = new FrameLayout(this);
            mFaceView = new FaceView(this);
            mCameraPreview = new CameraPreview(this, mFaceView);
            layout.addView(mCameraPreview);
            layout.addView(mFaceView);
            setContentView(layout);
        } catch (IOException e) {
            e.printStackTrace();
            new AlertDialog.Builder(this).setMessage(e.getMessage()).create().show();
        }
    }
    @Override
    protected void onDestroy(){
        super.onDestroy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}

// ----------------------------------------------------------------------

class FaceView extends View implements Camera.PreviewCallback {
    public static final int SUBSAMPLING_FACTOR = 4;

    private IplImage grayImage;
    private CvHaarClassifierCascade classifier;
    private CvMemStorage storage;
    private CvSeq faces;

    private FFmpegFrameRecorder recorder = null;
    private String filePath = null; // path of the temp video file
    private long startTime = 0;
    private Looper mAsyncHttpLooper;
    private LocationData mLocationData;

    private String targetURI = "https://acoustic.ifp.illinois.edu:8081";
    private String db = "publicDb";
    private String dev = "publicUser";
    private String pwd = "publicPwd";

    public FaceView(MainActivity context) throws IOException {
        super(context);

        // Create a private directory and file
        File classifierFile = new File(context.getDir("cascade", Context.MODE_PRIVATE), "haarcascade_frontalface_alt.xml");
        FileOutputStream os = new FileOutputStream(classifierFile);
        // load cascade file from application resources
        InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalface_alt);
        // copy from is to os
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
        }
        is.close();
        os.close();

        if (classifierFile == null || classifierFile.length() <= 0) {
            throw new IOException("Could not extract the classifier file from Java resource.");
        }

        // Preload the opencv_objdetect module to work around a known bug.
        Loader.load(opencv_objdetect.class);
        classifier = new CvHaarClassifierCascade(cvLoad(classifierFile.getAbsolutePath()));
        classifierFile.delete();
        if (classifier.isNull()) {
            throw new IOException("Could not load the classifier file.");
        }
        storage = CvMemStorage.create();

        // Preload the module to work around a known bug in FFmpegFrameRecorder
        Loader.load(swresample.class);

        // Create looper for asyncHttp
        HandlerThread thread2 = new HandlerThread("AsyncHttpResponseHandler",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mAsyncHttpLooper = thread2.getLooper();
        thread2.start();

        // temp video file
        File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        filePath = folder.getAbsolutePath() + "/Camera/" + "tmp" + ".mp4" ;

        // Create location
        mLocationData = new LocationData(getContext());
    }

    @Override
    public boolean onKeyDown(int keycode, KeyEvent event) {
        if (keycode == KeyEvent.KEYCODE_BACK){
            Log.i("onKeyDown", "KEYCODE_BACK");
            cvClearMemStorage(storage);
            mLocationData.stopLocationData();
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            performClick();
            return true;
        }
        return super.onKeyDown(keycode, event);
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        try {
            Size size = camera.getParameters().getPreviewSize();
            processImage(data, size.width, size.height);
            camera.addCallbackBuffer(data);
        } catch (RuntimeException e) {
            // The camera has probably just been released, ignore.
        }
    }

    protected void processImage(byte[] data, int width, int height) {
        // First, downsample our image and convert it into a grayscale IplImage
        int f = SUBSAMPLING_FACTOR;
        if (grayImage == null || grayImage.width() != width/f || grayImage.height() != height/f) {
            grayImage = IplImage.create(width/f, height/f, IPL_DEPTH_8U, 1);
        }
        int imageWidth  = grayImage.width();
        int imageHeight = grayImage.height();
        int dataStride = f*width;
        int imageStride = grayImage.widthStep();
        ByteBuffer imageBuffer = grayImage.getByteBuffer();
        for (int y = 0; y < imageHeight; y++) {
            int dataLine = y*dataStride;
            int imageLine = y*imageStride;
            for (int x = 0; x < imageWidth; x++) {
                imageBuffer.put(imageLine + x, data[dataLine + f*x]);
            }
        }

        cvClearMemStorage(storage);
        faces = cvHaarDetectObjects(grayImage, classifier, storage, 1.1, 3, CV_HAAR_DO_CANNY_PRUNING);
        postInvalidate();

        postProcessImage(data, width, height);
    }

    protected void postProcessImage(byte[] data, int width, int height){
        long duration = 0;

        // write frames to video
        if (faces!=null) {
            int total = faces.total();
            if (total > 0) {
                if (!MainActivity.faceState) {
                    MainActivity.faceState = true;
                    // create a new video
                    try {
                        recorder = new FFmpegFrameRecorder(filePath, width, height);
                        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                        recorder.setFormat("mp4");
                        recorder.setFrameRate(30);
                        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

                        recorder.start();
                        startTime = System.currentTimeMillis();
                        Log.i("MainActivity", "recorder started");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage());
                    }
                }
                // write a frame to the video track
                IplImage yuvIplimage = IplImage.create(width, height, IPL_DEPTH_8U, 2);
                yuvIplimage.getByteBuffer().put(data);
                try {
                    long t = 1000 * (System.currentTimeMillis() - startTime);
                    if (t > recorder.getTimestamp()) {
                        recorder.setTimestamp(t);
                    }
                    recorder.record(yuvIplimage);
                } catch (Exception ex) {
                    throw new RuntimeException(ex.getMessage());
                }
            } else {
                if (MainActivity.faceState) {
                    MainActivity.faceState = false;
                    // close the video track
                    try {
                        recorder.stop();
                        duration = System.currentTimeMillis() - startTime;
                        Log.i("MainActivity", "recorder stopped");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage());
                    }

                    if (duration > 1000) {
                        // *** Send video
                        RequestParams rpPut = new RequestParams();
                        rpPut.put("user", dev);
                        rpPut.put("passwd", pwd);
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"); // ISO8601 uses trailing Z
                        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                        String ts = sdf.format(new Date());
                        rpPut.put("filename", ts + ".mp4"); // name file using time stamp
                        TimedAsyncHttpResponseHandler httpHandler1 = new TimedAsyncHttpResponseHandler(mAsyncHttpLooper, getContext());
                        httpHandler1.executePut(targetURI + "/gridfs/" + db + "/v_data", rpPut, filePath);

                        // *** Send metadata
                        // prepare record date json
                        JSONObject recordDate = new JSONObject();
                        try {
                            recordDate.put("$date", ts);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        // prepare location json
                        JSONObject location = new JSONObject();
                        try {
                            location.put("type", "Point");
                            JSONArray coord = new JSONArray();
                            coord.put(mLocationData.getLongtitude());
                            coord.put(mLocationData.getLatitude());
                            location.put("coordinates", coord);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        // putting it together
                        JSONObject json = new JSONObject();
                        try {
                            json.put("filename", ts + ".mp4");
                            json.put("recordDate", recordDate);
                            json.put("location", location);
                            json.put("duration", duration); // in milliseconds
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                        RequestParams rpPost = new RequestParams();
                        rpPost.put("dbname", db);
                        rpPost.put("colname", "v_event");
                        rpPost.put("user", dev);
                        rpPost.put("passwd", pwd);
                        TimedAsyncHttpResponseHandler httpHandler2 = new TimedAsyncHttpResponseHandler(mAsyncHttpLooper, getContext());
                        httpHandler2.executePut(targetURI + "/write", rpPost, json);
                    }
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = new Paint();
        paint.setColor(Color.GREEN);
        paint.setTextSize(20);

        String s = "FacePreview - This side up.";
        float textWidth = paint.measureText(s);
        canvas.drawText(s, (getWidth()-textWidth)/2, 20, paint);

        if (faces != null) {
            paint.setStrokeWidth(5);
            paint.setStyle(Paint.Style.STROKE);
            float scaleX = (float)getWidth()/grayImage.width();
            float scaleY = (float)getHeight()/grayImage.height();
            int total = faces.total();
            for (int i = 0; i < total; i++) {
                CvRect r = new CvRect(cvGetSeqElem(faces, i));
                int x = r.x(), y = r.y(), w = r.width(), h = r.height();
                canvas.drawRect(x*scaleX, y*scaleY, (x+w)*scaleX, (y+h)*scaleY, paint);
            }
        }
    }
}

// ----------------------------------------------------------------------

class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    SurfaceHolder mHolder;
    Camera mCamera;
    Camera.PreviewCallback previewCallback;

    CameraPreview(Context context, Camera.PreviewCallback previewCallback) {
        super(context);
        this.previewCallback = previewCallback;

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        mCamera = Camera.open();
        try {
            mCamera.setPreviewDisplay(holder);
        } catch (IOException exception) {
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }


    private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.05;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Now that the size is known, set up the camera parameters and begin
        // the preview.
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewFpsRange(30000, 30000); // workaround due to the glass XE10 release

        List<Size> sizes = parameters.getSupportedPreviewSizes();
        Size optimalSize = getOptimalPreviewSize(sizes, w, h);
        parameters.setPreviewSize(optimalSize.width, optimalSize.height);

        mCamera.setParameters(parameters);

        if (previewCallback != null) {
            mCamera.setPreviewCallbackWithBuffer(previewCallback);
            Size size = parameters.getPreviewSize();
            byte[] data = new byte[size.width*size.height*
                    ImageFormat.getBitsPerPixel(parameters.getPreviewFormat())/8];
            mCamera.addCallbackBuffer(data);
        }
        mCamera.startPreview();
    }

}
