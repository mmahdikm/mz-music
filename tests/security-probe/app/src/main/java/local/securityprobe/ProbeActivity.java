package local.securityprobe;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private void log(String value) {
        Log.i("MZ_SECURITY", value);
        try (FileOutputStream out = openFileOutput("results.txt", MODE_APPEND)) {
            out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        log("MODE " + mode);
        if ("preview".equals(mode)) {
            try {
                startActivity(new Intent().setComponent(new ComponentName("local.music", "androidx.compose.ui.tooling.PreviewActivity"))
                    .putExtra("composable", "local.review.Nonexistent.render"));
                log("Unprivileged PreviewActivity launch accepted");
            } catch (Exception e) { log("Preview rejected: " + e); }
            return;
        }
        var future = new MediaController.Builder(this, new SessionToken(this,
            new ComponentName("local.music", "local.music.playback.PlaybackService"))).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                log("CONNECTED queue=" + controller.getMediaItemCount() + " soundExtras=" + controller.getSessionExtras().containsKey("soundDocument"));
                log("Metadata=" + controller.getMediaMetadata().title);
                if ("trusted".equals(mode)) {
                    var manager = (android.media.session.MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
                    for (var platform : manager.getActiveSessions(new ComponentName(this, ProbeListener.class))) {
                        if (platform.getPackageName().equals("local.music") && platform.getQueue()!=null) {
                            for (var item : platform.getQueue()) log("LEGACY queue title=" + item.getDescription().getTitle());
                        }
                    }
                }
                for (String name : new String[]{"COMMAND_PLAY_PAUSE", "COMMAND_CHANGE_MEDIA_ITEMS", "COMMAND_SET_MEDIA_ITEM", "COMMAND_SET_SPEED_AND_PITCH", "COMMAND_SET_AUDIO_ATTRIBUTES", "COMMAND_SET_VOLUME", "COMMAND_GET_TIMELINE", "COMMAND_SET_SHUFFLE_MODE", "COMMAND_SET_REPEAT_MODE"}) {
                    int command = Player.class.getField(name).getInt(null);
                    log(name + "=" + controller.isCommandAvailable(command));
                }
                var sound = new SessionCommand("local.music.SOUND", Bundle.EMPTY);
                log("SOUND advertised=" + controller.isSessionCommandAvailable(sound));
                Bundle arguments = new Bundle(); arguments.putString("op", "tuned"); arguments.putBoolean("value", true);
                var result = controller.sendCustomCommand(sound, arguments);
                result.addListener(() -> { try { log("SOUND result=" + result.get().resultCode); } catch (Exception e) { log("SOUND failure=" + e); } }, getMainExecutor());
                var queue = new SessionCommand("local.music.QUEUE", Bundle.EMPTY);
                log("QUEUE advertised=" + controller.isSessionCommandAvailable(queue));
                Bundle query = new Bundle(); query.putString("op", "get"); query.putInt("offset", 0);
                var queueResult = controller.sendCustomCommand(queue, query);
                queueResult.addListener(() -> { try { log("QUEUE result=" + queueResult.get().resultCode); } catch (Exception e) { log("QUEUE failure=" + e); } }, getMainExecutor());
                if ("trusted".equals(mode)) {
                    controller.setPlaybackSpeed(1.25f);
                    handler.postDelayed(() -> {
                        log("Speed observed=" + controller.getPlaybackParameters().speed);
                        controller.setPlaybackSpeed(1f);
                        int before = controller.getMediaItemCount();
                        String uri = getIntent().getStringExtra("uri");
                        if (uri != null) {
                            controller.setMediaItem(MediaItem.fromUri(uri));
                            handler.postDelayed(() -> log("Rejected URI queue before=" + before + " after=" + controller.getMediaItemCount() + " current=" + (controller.getCurrentMediaItem() == null ? "null" : controller.getCurrentMediaItem().mediaId)), 1000);
                        }
                    }, 1000);
                }
            } catch (Exception e) { log("REJECTED " + e); }
        }, getMainExecutor());
    }
    @Override public void onDestroy() { if (controller != null) controller.release(); super.onDestroy(); }
}
