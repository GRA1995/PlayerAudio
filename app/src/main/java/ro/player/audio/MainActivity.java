package ro.player.audio;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    // Culori
    private static final int BG = Color.parseColor("#121418");
    private static final int PANEL = Color.parseColor("#1C1F26");
    private static final int TEXT = Color.parseColor("#ECEEF2");
    private static final int MUTED = Color.parseColor("#8A90A0");
    private static final int ACCENT = Color.parseColor("#FF7A45");

    static class Track {
        long id;
        String title;
        String artist;
        long durationMs;
        Uri uri;
    }

    private final List<Track> allTracks = new ArrayList<>();
    private final List<Track> shown = new ArrayList<>();
    private int currentIndex = -1;          // index în allTracks

    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean shuffle = false;
    private int repeatMode = 0;             // 0 = fără, 1 = toate, 2 = una
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // UI
    private TrackAdapter adapter;
    private TextView nowTitle, nowArtist, timeCur, timeTotal, emptyView;
    private SeekBar seekBar;
    private Button btnPlay, btnShuffle, btnRepeat;
    private boolean userSeeking = false;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // căștile au fost scoase -> pauză
            if (player != null && player.isPlaying()) pause();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (player == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player.isPlaying()) pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            player.setVolume(0.3f, 0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            player.setVolume(1f, 1f);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && !userSeeking) {
                try {
                    int pos = player.getCurrentPosition();
                    seekBar.setProgress(pos);
                    timeCur.setText(fmt(pos));
                } catch (IllegalStateException ignored) { }
            }
            handler.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- ciclu de viață

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(PANEL);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, f);
        }

        if (hasPermission()) loadTracks();
        else requestPermissions(new String[]{permissionName()}, REQ_PERM);

        handler.post(ticker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        releasePlayer();
        abandonFocus();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                loadTracks();
            } else {
                emptyView.setText("Fără acces la fișierele audio.\nAtinge aici pentru a cere din nou permisiunea.");
                emptyView.setOnClickListener(v -> requestPermissions(new String[]{permissionName()}, REQ_PERM));
            }
        }
    }

    private String permissionName() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- încărcare piese

    private void loadTracks() {
        allTracks.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };
        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor c = getContentResolver().query(collection, proj, sel, null, order)) {
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                while (c.moveToNext()) {
                    Track t = new Track();
                    t.id = c.getLong(iId);
                    t.title = c.getString(iTitle);
                    t.artist = c.getString(iArtist);
                    if (t.artist == null || "<unknown>".equals(t.artist)) t.artist = "Artist necunoscut";
                    t.durationMs = c.getLong(iDur);
                    t.uri = ContentUris.withAppendedId(collection, t.id);
                    allTracks.add(t);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Eroare la citirea pieselor: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        filter("");
    }

    private void filter(String q) {
        shown.clear();
        String s = q.trim().toLowerCase(Locale.ROOT);
        for (Track t : allTracks) {
            if (s.isEmpty()
                    || (t.title != null && t.title.toLowerCase(Locale.ROOT).contains(s))
                    || (t.artist != null && t.artist.toLowerCase(Locale.ROOT).contains(s))) {
                shown.add(t);
            }
        }
        adapter.notifyDataSetChanged();
        if (allTracks.isEmpty()) {
            emptyView.setText("Nu am găsit piese audio pe telefon.");
            emptyView.setVisibility(View.VISIBLE);
        } else if (shown.isEmpty()) {
            emptyView.setText("Nicio piesă nu se potrivește căutării.");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- redare

    private void playIndex(int index) {
        if (index < 0 || index >= allTracks.size()) return;
        currentIndex = index;
        Track t = allTracks.get(index);
        releasePlayer();
        if (!requestFocus()) {
            Toast.makeText(this, "Alt program folosește sunetul acum.", Toast.LENGTH_SHORT).show();
        }
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            seekBar.setMax(mp.getDuration());
            timeTotal.setText(fmt(mp.getDuration()));
            mp.start();
            updatePlayButton();
        });
        player.setOnCompletionListener(mp -> onTrackFinished());
        player.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Nu pot reda: " + t.title, Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::next, 300);
            return true;
        });
        try {
            player.setDataSource(this, t.uri);
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Nu pot deschide: " + t.title, Toast.LENGTH_SHORT).show();
            return;
        }
        nowTitle.setText(t.title);
        nowArtist.setText(t.artist);
        seekBar.setProgress(0);
        timeCur.setText(fmt(0));
        timeTotal.setText(fmt(t.durationMs));
        adapter.notifyDataSetChanged();
        updatePlayButton();
    }

    private void togglePlay() {
        if (player == null) {
            if (!shown.isEmpty()) playIndex(allTracks.indexOf(shown.get(0)));
            return;
        }
        if (player.isPlaying()) pause();
        else {
            requestFocus();
            player.setVolume(1f, 1f);
            player.start();
        }
        updatePlayButton();
    }

    private void pause() {
        if (player != null) player.pause();
        updatePlayButton();
    }

    private void next() {
        if (allTracks.isEmpty()) return;
        if (shuffle && allTracks.size() > 1) {
            int n;
            do { n = random.nextInt(allTracks.size()); } while (n == currentIndex);
            playIndex(n);
        } else {
            playIndex((currentIndex + 1) % allTracks.size());
        }
    }

    private void previous() {
        if (allTracks.isEmpty()) return;
        // dacă piesa a trecut de 3 secunde, o reia de la început
        if (player != null && player.getCurrentPosition() > 3000) {
            player.seekTo(0);
            return;
        }
        int p = currentIndex - 1;
        if (p < 0) p = allTracks.size() - 1;
        playIndex(p);
    }

    private void onTrackFinished() {
        if (repeatMode == 2) { playIndex(currentIndex); return; }
        boolean last = currentIndex >= allTracks.size() - 1;
        if (!shuffle && last && repeatMode == 0) {
            updatePlayButton();
            return;
        }
        next();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    private boolean requestFocus() {
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            r = audioManager.requestAudioFocus(focusRequest);
        } else {
            r = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Titlu
        TextView header = text("Player Audio", 26, TEXT, true);
        header.setPadding(dp(20), dp(20), dp(20), dp(8));
        root.addView(header);

        // Căutare
        EditText search = new EditText(this);
        search.setHint("Caută piesă sau artist");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setPadding(dp(16), dp(12), dp(16), dp(12));
        search.setBackground(rounded(PANEL, dp(12)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(16), dp(4), dp(16), dp(8));
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) { }
        });

        // Listă + mesaj gol
        emptyView = text("Se încarcă…", 15, MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> playIndex(allTracks.indexOf(shown.get(pos))));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Panou de redare
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(20), dp(14), dp(20), dp(18));

        nowTitle = text("Nicio piesă selectată", 17, TEXT, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        nowTitle.setMarqueeRepeatLimit(-1);
        nowTitle.setSelected(true);
        panel.addView(nowTitle);
        nowArtist = text("Alege o piesă din listă", 13, MUTED, false);
        nowArtist.setSingleLine(true);
        panel.addView(nowArtist);

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(-1, -2);
        skp.topMargin = dp(10);
        panel.addView(seekBar, skp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeCur.setText(fmt(p));
            }
            public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null) player.seekTo(s.getProgress());
            }
        });

        LinearLayout times = new LinearLayout(this);
        timeCur = text("0:00", 12, MUTED, false);
        timeTotal = text("0:00", 12, MUTED, false);
        timeTotal.setGravity(Gravity.END);
        times.addView(timeCur, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(timeTotal, new LinearLayout.LayoutParams(0, -2, 1f));
        times.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(times);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, 0);
        btnShuffle = control("⤮", 22, false);
        Button btnPrev = control("⏮", 26, false);
        btnPlay = control("▶", 30, true);
        Button btnNext = control("⏭", 26, false);
        btnRepeat = control("↻", 22, false);
        controls.addView(btnShuffle, ctrlParams(48));
        controls.addView(btnPrev, ctrlParams(56));
        controls.addView(btnPlay, ctrlParams(72));
        controls.addView(btnNext, ctrlParams(56));
        controls.addView(btnRepeat, ctrlParams(48));
        panel.addView(controls);

        btnPlay.setOnClickListener(v -> togglePlay());
        btnNext.setOnClickListener(v -> next());
        btnPrev.setOnClickListener(v -> previous());
        btnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            btnShuffle.setTextColor(shuffle ? ACCENT : MUTED);
            Toast.makeText(this, shuffle ? "Amestecare pornită" : "Amestecare oprită", Toast.LENGTH_SHORT).show();
        });
        btnRepeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            String[] labels = {"Fără repetare", "Repetă toate", "Repetă piesa"};
            btnRepeat.setText(repeatMode == 2 ? "↻1" : "↻");
            btnRepeat.setTextColor(repeatMode == 0 ? MUTED : ACCENT);
            Toast.makeText(this, labels[repeatMode], Toast.LENGTH_SHORT).show();
        });

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void updatePlayButton() {
        boolean playing = player != null && player.isPlaying();
        btnPlay.setText(playing ? "⏸" : "▶");
    }

    private class TrackAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return shown.get(p); }
        public long getItemId(int p) { return shown.get(p).id; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView title, sub;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));
                title = text("", 16, TEXT, false);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                sub = text("", 13, MUTED, false);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);
                row.addView(sub);
                row.setTag(new TextView[]{title, sub});
            } else {
                row = (LinearLayout) convert;
                TextView[] tv = (TextView[]) row.getTag();
                title = tv[0];
                sub = tv[1];
            }
            Track t = shown.get(pos);
            boolean current = currentIndex >= 0 && allTracks.get(currentIndex) == t;
            title.setText(t.title);
            title.setTextColor(current ? ACCENT : TEXT);
            title.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            sub.setText(t.artist + "  ·  " + fmt(t.durationMs));
            return row;
        }
    }

    // ---------------------------------------------------------------- utilitare

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button control(String label, int sp, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setPadding(0, 0, 0, 0);
        b.setTextColor(primary ? Color.WHITE : (label.startsWith("⤮") || label.startsWith("↻") ? MUTED : TEXT));
        b.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(primary ? ACCENT : Color.TRANSPARENT);
        b.setBackground(d);
        return b;
    }

    private LinearLayout.LayoutParams ctrlParams(int sizeDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        p.setMargins(dp(8), 0, dp(8), 0);
        return p;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
