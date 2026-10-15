package com.zkincamcam.xcam.hooks;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.zkincamcam.xcam.data.Constants;
import com.zkincamcam.xcam.utils.MediaRemuxer;
import com.zkincamcam.xcam.xposed.XCamModule;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import androidx.media3.common.util.UnstableApi;

/**
 * Consolidated Capture Hook.
 * Handles all image data injection points (BitmapFactory, Bitmap.compress, MediaStore, FileOutput).
 * Prevents redundant extractions and recursion.
 */
@UnstableApi
public class CaptureHook {
    private final XCamModule module;
    private final Map<MediaRecorder, RecorderState> recorderStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<FileDescriptor, Uri> outputUris =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class RecorderState {
        String targetPath;
        Uri targetUri;
        String sourcePath;
        long startPositionMs;
        long startedAtMs;
    }

    public CaptureHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Capture Diversion Hooks");
            
            // 1. BitmapFactory (Byte Array & Stream)
            hookBitmapFactory(param);
            
            // 2. Bitmap.compress
            hookBitmapCompress(param);
            
            // 3. FileOutputStream (Direct file writes)
            hookFileOutputStream(param);
            
            // 4. ContentResolver (MediaStore / Scoped Storage)
            hookMediaStore(param);

            // 5. Replace encoded camera video with the matching virtual-video segment
            hookMediaRecorder();
            
            module.logHook("[+] Capture Diversion Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Capture Hooks installation failed: " + t.getMessage());
        }
    }

    private void hookMediaRecorder() {
        try {
            Method setOutputPath = MediaRecorder.class.getDeclaredMethod("setOutputFile", String.class);
            module.hook(setOutputPath).intercept(chain -> {
                Object result = chain.proceed();
                MediaRecorder recorder = (MediaRecorder) chain.getThisObject();
                synchronized (recorderStates) {
                    RecorderState state = recorderStates.get(recorder);
                    if (state == null) {
                        state = new RecorderState();
                        recorderStates.put(recorder, state);
                    }
                    state.targetPath = (String) chain.getArgs().get(0);
                    state.targetUri = null;
                }
                return result;
            });

            Method setOutputDescriptor = MediaRecorder.class.getDeclaredMethod("setOutputFile", FileDescriptor.class);
            module.hook(setOutputDescriptor).intercept(chain -> {
                Object result = chain.proceed();
                MediaRecorder recorder = (MediaRecorder) chain.getThisObject();
                FileDescriptor descriptor = (FileDescriptor) chain.getArgs().get(0);
                Uri outputUri = outputUris.get(descriptor);
                synchronized (recorderStates) {
                    RecorderState state = recorderStates.get(recorder);
                    if (state == null) {
                        state = new RecorderState();
                        recorderStates.put(recorder, state);
                    }
                    state.targetPath = null;
                    state.targetUri = outputUri;
                }
                if (outputUri == null) {
                    module.logHook("[VIDEO] MediaRecorder file descriptor could not be mapped to a content URI");
                }
                return result;
            });

            Method start = MediaRecorder.class.getDeclaredMethod("start");
            module.hook(start).intercept(chain -> {
                MediaRecorder recorder = (MediaRecorder) chain.getThisObject();
                String sourcePath = module.getMediaPath();
                long startPositionMs = module.getPlaybackPositionMs();
                long startedAtMs = SystemClock.elapsedRealtime();
                Object result = chain.proceed();
                if (sourcePath != null) {
                    synchronized (recorderStates) {
                        RecorderState state = recorderStates.get(recorder);
                        if (state == null) {
                            state = new RecorderState();
                            recorderStates.put(recorder, state);
                        }
                        state.sourcePath = sourcePath;
                        state.startPositionMs = startPositionMs;
                        state.startedAtMs = startedAtMs;
                    }
                }
                return result;
            });

            Method stop = MediaRecorder.class.getDeclaredMethod("stop");
            module.hook(stop).intercept(chain -> {
                Object result = chain.proceed();
                MediaRecorder recorder = (MediaRecorder) chain.getThisObject();
                RecorderState completed;
                synchronized (recorderStates) {
                    RecorderState state = recorderStates.get(recorder);
                    completed = state == null ? null : new RecorderState();
                    if (completed != null) {
                        completed.targetPath = state.targetPath;
                        completed.targetUri = state.targetUri;
                        completed.sourcePath = state.sourcePath;
                        completed.startPositionMs = state.startPositionMs;
                        completed.startedAtMs = state.startedAtMs;
                        state.sourcePath = null;
                    }
                }
                remuxRecording(completed);
                return result;
            });

            Method reset = MediaRecorder.class.getDeclaredMethod("reset");
            module.hook(reset).intercept(chain -> {
                Object result = chain.proceed();
                recorderStates.remove((MediaRecorder) chain.getThisObject());
                return result;
            });

            Method release = MediaRecorder.class.getDeclaredMethod("release");
            module.hook(release).intercept(chain -> {
                Object result = chain.proceed();
                recorderStates.remove((MediaRecorder) chain.getThisObject());
                return result;
            });
            module.logHook("[+] Hooked: MediaRecorder start/stop/output/reset/release");
        } catch (Throwable t) {
            module.logHook("[!] MediaRecorder hook installation failed: " + t.getMessage());
        }
    }

    private void remuxRecording(RecorderState state) {
        if (state == null || state.sourcePath == null ||
                (state.targetPath == null && state.targetUri == null)) return;
        long durationMs = SystemClock.elapsedRealtime() - state.startedAtMs;
        if (durationMs <= 0) {
            module.logHook("[VIDEO] Invalid MediaRecorder duration; leaving original recording unchanged");
            return;
        }

        File temporaryUriOutput = null;
        boolean contentUriTarget = state.targetUri != null;
        try {
            Context context = module.getApplicationContextForHooks();
            if (context == null) {
                module.logHook("[VIDEO] App context is unavailable; leaving original recording unchanged");
                return;
            }

            File targetFile;
            if (contentUriTarget) {
                targetFile = new File(context.getCacheDir(), "xcam_record_" + System.nanoTime() + ".mp4");
                temporaryUriOutput = targetFile;
                if (state.sourcePath.equals(state.targetUri.toString())) {
                    module.logHook("[VIDEO] Source and recording output are the same URI; post-processing skipped");
                    return;
                }
            } else {
                targetFile = new File(state.targetPath);
                if (!targetFile.isFile()) {
                    module.logHook("[VIDEO] Output file is unavailable for post-processing: " + state.targetPath);
                    return;
                }
                if (new File(state.sourcePath).getCanonicalFile().equals(targetFile.getCanonicalFile())) {
                    module.logHook("[VIDEO] Source and recording output are the same file; post-processing skipped");
                    return;
                }
            }

            module.logHook("[VIDEO] Replacing MediaRecorder output with virtual video segment"
                    + " (start=" + state.startPositionMs + " ms, duration=" + durationMs + " ms)");
            boolean remuxed = module.runIgnoringHooks(() -> MediaRemuxer.INSTANCE.trimVideo(
                    context,
                    state.sourcePath,
                    targetFile,
                    state.startPositionMs,
                    durationMs,
                    message -> {
                        module.logHook(message);
                        return kotlin.Unit.INSTANCE;
                    }
            ));
            if (!remuxed) {
                module.logHook("[VIDEO] Remux failed; original MediaRecorder output was preserved");
                return;
            }
            if (contentUriTarget) {
                module.runIgnoringHooks(() -> {
                    try {
                        replaceContentUriOutput(
                                context.getContentResolver(),
                                state.targetUri,
                                targetFile,
                                context.getCacheDir()
                        );
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to replace content URI recording safely", e);
                    }
                    return null;
                });
                module.logHook("[VIDEO] Remuxed recording copied to content URI");
            }
        } catch (Throwable t) {
            module.logHook("[VIDEO] Post-processing failed: " + t.getMessage());
        } finally {
            if (temporaryUriOutput != null) temporaryUriOutput.delete();
        }
    }

    private void replaceContentUriOutput(
            ContentResolver resolver,
            Uri targetUri,
            File replacementFile,
            File cacheDirectory
    ) throws IOException {
        File backupFile = new File(cacheDirectory, "xcam_record_backup_" + System.nanoTime() + ".mp4");
        try {
            try (InputStream input = resolver.openInputStream(targetUri)) {
                if (input == null) {
                    throw new IOException("Content URI cannot be read; original recording was not modified");
                }
                copy(input, backupFile);
            }
            if (!backupFile.isFile() || backupFile.length() == 0) {
                throw new IOException("Could not create a complete backup of the original recording");
            }

            try {
                copyFileToContentUri(replacementFile, resolver, targetUri);
                long writtenLength = getContentUriLength(resolver, targetUri);
                if (writtenLength != replacementFile.length()) {
                    throw new IOException("Content URI size verification failed: expected "
                            + replacementFile.length() + " bytes but found " + writtenLength);
                }
            } catch (Exception writeFailure) {
                try {
                    copyFileToContentUri(backupFile, resolver, targetUri);
                } catch (Exception restoreFailure) {
                    writeFailure.addSuppressed(restoreFailure);
                    module.logHook("[VIDEO] CRITICAL: failed to restore original recording after URI write failure: "
                            + restoreFailure.getMessage());
                }
                if (writeFailure instanceof IOException) {
                    throw (IOException) writeFailure;
                }
                throw new IOException("Content URI write failed", writeFailure);
            }
        } finally {
            if (backupFile.exists() && !backupFile.delete()) {
                module.logHook("[VIDEO] Could not delete temporary recording backup: " + backupFile);
            }
        }
    }

    private static long getContentUriLength(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Content URI cannot be reopened to verify the recording");
            }
            byte[] buffer = new byte[64 * 1024];
            long length = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
            }
            return length;
        }
    }

    private static void copyFileToContentUri(File sourceFile, ContentResolver resolver, Uri targetUri)
            throws IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(sourceFile);
             OutputStream output = resolver.openOutputStream(targetUri, "wt")) {
            if (output == null) {
                throw new IOException("ContentResolver returned no output stream for " + targetUri);
            }
            copy(input, output);
            output.flush();
        }
    }

    private static void copy(InputStream input, File destination) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
            output.flush();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private void hookBitmapFactory(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                // 1. Hook decodeByteArray
                if (method.getName().equals("decodeByteArray")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            byte[] injected = module.handleCapture(0, 0); // Use 0,0 for default/cached
                            if (injected != null) {
                                module.logHook("[*] Activity: Captured -> Injected into BitmapFactory#decodeByteArray");
                                module.recordPipelineNode("BitmapFactory(decodeByteArray)");
                                Object[] args = chain.getArgs().toArray();
                                args[0] = injected;
                                if (args.length >= 3) args[2] = injected.length;
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeByteArray");
                }
                
                // 2. Hook decodeStream - Return injected Bitmap directly if capturing
                // This is more reliable for apps that use streams for camera frames
                if (method.getName().equals("decodeStream") && method.getParameterTypes().length >= 1) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            byte[] injected = module.handleCapture(0, 0);
                            if (injected != null) {
                                // Use Options if provided in args
                                BitmapFactory.Options opts = null;
                                if (chain.getArgs().size() >= 3 && chain.getArgs().get(2) instanceof BitmapFactory.Options) {
                                    final BitmapFactory.Options o = (BitmapFactory.Options) chain.getArgs().get(2);
                                    opts = o;
                                }

                                final BitmapFactory.Options finalOpts = opts;
                                Bitmap bitmap = module.runIgnoringHooks(() ->
                                        BitmapFactory.decodeByteArray(injected, 0, injected.length, finalOpts));
                                if (bitmap != null) {
                                    module.logHook("[*] Activity: Captured -> Injected virtual Bitmap into BitmapFactory#decodeStream");
                                        module.recordPipelineNode("BitmapFactory(decodeStream)");
                                    return bitmap;
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeStream");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookBitmapCompress(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method compress = Bitmap.class.getDeclaredMethod("compress", Bitmap.CompressFormat.class, int.class, OutputStream.class);
            module.hook(compress).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                if (module.isCapturingState() && module.getMediaPath() != null) {
                    byte[] injected = module.handleCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT);
                    if (injected != null) {
                        OutputStream os = (OutputStream) chain.getArgs().get(2);
                        if (os != null) {
                            try {
                                module.runIgnoringHooks(() -> {
                                    try {
                                        os.write(injected);
                                        os.flush();
                                    } catch (Throwable t) {
                                        module.logHook("[!] Bitmap#compress injection FAILED: " + t.getMessage());
                                        return false;
                                    }
                                    module.logHook("[*] Activity: Captured -> Injected into Bitmap#compress");
                                    return true;
                                });
                                return true;
                            } catch (Throwable t) {
                                module.logHook("[!] Bitmap#compress injection FAILED: " + t.getMessage());
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Bitmap#compress");
        } catch (Throwable ignored) {}
    }

    private void hookFileOutputStream(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method write = FileOutputStream.class.getDeclaredMethod("write", byte[].class, int.class, int.class);
            module.hook(write).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                if (module.isCapturingState() && module.getMediaPath() != null) {
                    byte[] data = (byte[]) chain.getArgs().get(0);
                    int offset = (int) chain.getArgs().get(1);
                    int len = (int) chain.getArgs().get(2);
                    // Only divert small, capture-sized JPEG payloads. Full-size camera stills hit
                    // this path; thumbnails, downloads and other app JPEG writes are far smaller
                    // or larger and must pass through untouched.
                    int captureMin = Constants.DEFAULT_CAPTURE_WIDTH * Constants.DEFAULT_CAPTURE_HEIGHT / 8;
                    int captureMax = Constants.DEFAULT_CAPTURE_WIDTH * Constants.DEFAULT_CAPTURE_HEIGHT * 2;
                    if (data != null && offset >= 0 && len >= 2 && offset <= data.length - len
                            && len > captureMin && len < captureMax
                            && (data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0xD8) {
                        byte[] injected = module.handleCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT);
                        if (injected != null) {
                            module.logHook("[*] Activity: Captured -> Injected into FileOutputStream#write (" + len + " bytes)");
                            Object[] args = chain.getArgs().toArray();
                            args[0] = injected;
                            args[1] = 0;
                            args[2] = injected.length;
                            return chain.proceed(args);
                        }
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: FileOutputStream#write");
        } catch (Throwable ignored) {}
    }

    private void hookMediaStore(XposedModuleInterface.PackageReadyParam param) {
        try {
            // openFileDescriptor (Scoped Storage)
            Method openFD = ContentResolver.class.getDeclaredMethod("openFileDescriptor", Uri.class, String.class);
            module.hook(openFD).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                
                Uri uri = (Uri) chain.getArgs().get(0);
                String mode = (String) chain.getArgs().get(1);
                
                if (uri != null && mode != null && mode.contains("w")) {
                    ParcelFileDescriptor pfd = (ParcelFileDescriptor) chain.proceed();
                    if (pfd != null) {
                        outputUris.put(pfd.getFileDescriptor(), uri);
                        String mimeType = null;
                        try {
                            mimeType = ((ContentResolver) chain.getThisObject()).getType(uri);
                        } catch (Throwable t) {
                            module.logHook("[!] Unable to determine MediaStore output type: " + t.getMessage());
                        }
                        if (module.isCapturingState() && mimeType != null && mimeType.startsWith("image/")) {
                            byte[] injected = module.handleCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT);
                            if (injected != null) {
                                try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(pfd.getFileDescriptor())) {
                                    module.runIgnoringHooks(() -> {
                                        try {
                                            FileOutputStream fos = new FileOutputStream(duplicate.getFileDescriptor());
                                            fos.write(injected);
                                            fos.flush();
                                            fos.getFD().sync();
                                        } catch (Throwable t) {
                                            module.logHook("[!] MediaStore injection error: " + t.getMessage());
                                            throw new IllegalStateException("MediaStore image injection failed", t);
                                        }
                                        module.logHook("[*] Activity: Captured -> Injected into MediaStore FD: " + uri);
                                        return null;
                                    });
                                } catch (Throwable t) {
                                    module.logHook("[!] MediaStore injection error: " + t.getMessage());
                                }
                            }
                        }
                    }
                    return pfd;
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: ContentResolver#openFileDescriptor");
        } catch (Throwable ignored) {}
    }
}
