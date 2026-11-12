package com.kanyonkan.unirvideos;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    private final List<Uri> uris = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private LinearLayout listBox;
    private Button btnAdd, btnMerge, btnOpen;
    private ProgressBar bar;
    private TextView status;
    private Uri resultUri;
    private volatile boolean working = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Unir Vídeos MP4");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Os vídeos devem ter o mesmo codec e a mesma resolução. A união é feita sem recodificar (rápida e sem perda de qualidade).");
        hint.setTextSize(13);
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        btnAdd = new Button(this);
        btnAdd.setText("Adicionar vídeos");
        btnAdd.setOnClickListener(v -> pick());
        root.addView(btnAdd);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        btnMerge = new Button(this);
        btnMerge.setText("Unir vídeos");
        btnMerge.setOnClickListener(v -> startMerge());
        root.addView(btnMerge);

        btnOpen = new Button(this);
        btnOpen.setText("Abrir vídeo unido");
        btnOpen.setVisibility(View.GONE);
        btnOpen.setOnClickListener(v -> {
            if (resultUri == null) return;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(resultUri, "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(i);
            } catch (Exception e) {
                status.setText("Nenhum app para abrir o vídeo.");
            }
        });
        root.addView(btnOpen);

        setContentView(root);
        refreshList();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) addUri(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            addUri(data.getData());
        }
        refreshList();
    }

    private void addUri(Uri u) {
        uris.add(u);
        names.add(displayName(u));
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return "vídeo";
    }

    private void refreshList() {
        listBox.removeAllViews();
        for (int i = 0; i < uris.size(); i++) {
            final int pos = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView t = new TextView(this);
            t.setText((i + 1) + ". " + names.get(i));
            t.setTextColor(Color.BLACK);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

            row.addView(smallBtn("▲", v -> move(pos, -1)));
            row.addView(smallBtn("▼", v -> move(pos, 1)));
            row.addView(smallBtn("✕", v -> {
                if (working) return;
                uris.remove(pos);
                names.remove(pos);
                refreshList();
            }));
            listBox.addView(row);
        }
        btnMerge.setEnabled(uris.size() >= 2 && !working);
        btnAdd.setEnabled(!working);
        if (uris.size() < 2 && !working) status.setText("Adicione pelo menos 2 vídeos.");
    }

    private Button smallBtn(String s, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setMinWidth(dp(44));
        bt.setMinimumWidth(dp(44));
        bt.setOnClickListener(l);
        return bt;
    }

    private void move(int pos, int d) {
        if (working) return;
        int n = pos + d;
        if (n < 0 || n >= uris.size()) return;
        Uri u = uris.remove(pos);
        String s = names.remove(pos);
        uris.add(n, u);
        names.add(n, s);
        refreshList();
    }

    private void startMerge() {
        if (working || uris.size() < 2) return;
        working = true;
        resultUri = null;
        btnOpen.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshList();
        final List<Uri> in = new ArrayList<>(uris);
        new Thread(() -> {
            String msg;
            try {
                merge(in);
                msg = "Concluído! Salvo em Filmes/UnirVideos.";
            } catch (Exception e) {
                resultUri = null;
                msg = "Erro: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                working = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(m);
                if (resultUri != null) btnOpen.setVisibility(View.VISIBLE);
                refreshList();
                status.setText(m);
            });
        }).start();
    }

    private void progress(float f, String text) {
        runOnUiThread(() -> {
            bar.setProgress((int) (f * 1000));
            status.setText(text);
        });
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int intOr(MediaFormat f, String key, int def) {
        return f.containsKey(key) ? f.getInteger(key) : def;
    }

    private void merge(List<Uri> in) throws Exception {
        ContentResolver cr = getContentResolver();
        String fileName = "Unido_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        Uri outUri;
        ParcelFileDescriptor outPfd;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/UnirVideos");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            outUri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (outUri == null) throw new Exception("não foi possível criar o arquivo de saída");
            outPfd = cr.openFileDescriptor(outUri, "rw");
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) throw new Exception("armazenamento indisponível");
            dir.mkdirs();
            File f = new File(dir, fileName);
            outUri = Uri.fromFile(f);
            outPfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }

        MediaMuxer muxer = null;
        boolean started = false;
        boolean ok = false;
        try {
            muxer = new MediaMuxer(outPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int outV = -1, outA = -1;
            String vMime = null, aMime = null;
            int vW = 0, vH = 0, aSr = 0, aCh = 0;
            boolean hasAudio = false;
            long offset = 0;
            ByteBuffer buf = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            for (int i = 0; i < in.size(); i++) {
                String label = "Unindo vídeo " + (i + 1) + " de " + in.size() + "...";
                progress((float) i / in.size(), label);

                ParcelFileDescriptor pfd = cr.openFileDescriptor(in.get(i), "r");
                if (pfd == null) throw new Exception("não foi possível abrir o vídeo " + (i + 1));
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(pfd.getFileDescriptor());
                    int vIdx = findTrack(ex, "video/");
                    int aIdx = findTrack(ex, "audio/");
                    if (vIdx < 0) throw new Exception("o arquivo " + (i + 1) + " não tem faixa de vídeo");
                    MediaFormat vf = ex.getTrackFormat(vIdx);
                    MediaFormat af = aIdx >= 0 ? ex.getTrackFormat(aIdx) : null;

                    if (i == 0) {
                        vMime = vf.getString(MediaFormat.KEY_MIME);
                        vW = vf.getInteger(MediaFormat.KEY_WIDTH);
                        vH = vf.getInteger(MediaFormat.KEY_HEIGHT);
                        int rot = 0;
                        MediaMetadataRetriever mr = new MediaMetadataRetriever();
                        try {
                            mr.setDataSource(pfd.getFileDescriptor());
                            String r = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                            if (r != null) rot = Integer.parseInt(r);
                        } catch (Exception ignored) {
                        } finally {
                            mr.release();
                        }
                        outV = muxer.addTrack(vf);
                        muxer.setOrientationHint(rot);
                        if (af != null) {
                            hasAudio = true;
                            aMime = af.getString(MediaFormat.KEY_MIME);
                            aSr = intOr(af, MediaFormat.KEY_SAMPLE_RATE, 44100);
                            aCh = intOr(af, MediaFormat.KEY_CHANNEL_COUNT, 2);
                            outA = muxer.addTrack(af);
                        }
                        muxer.start();
                        started = true;
                    } else {
                        if (!vMime.equals(vf.getString(MediaFormat.KEY_MIME))
                                || vW != vf.getInteger(MediaFormat.KEY_WIDTH)
                                || vH != vf.getInteger(MediaFormat.KEY_HEIGHT)) {
                            throw new Exception("o vídeo " + (i + 1) + " tem codec ou resolução diferente do primeiro");
                        }
                        if (hasAudio) {
                            if (af == null) throw new Exception("o vídeo " + (i + 1) + " não tem áudio, mas o primeiro tem");
                            if (!aMime.equals(af.getString(MediaFormat.KEY_MIME))
                                    || aSr != intOr(af, MediaFormat.KEY_SAMPLE_RATE, aSr)
                                    || aCh != intOr(af, MediaFormat.KEY_CHANNEL_COUNT, aCh)) {
                                throw new Exception("o áudio do vídeo " + (i + 1) + " tem formato diferente do primeiro");
                            }
                        }
                    }

                    ex.selectTrack(vIdx);
                    boolean useAudio = hasAudio && aIdx >= 0;
                    if (useAudio) ex.selectTrack(aIdx);

                    long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
                    int fps = intOr(vf, MediaFormat.KEY_FRAME_RATE, 30);
                    if (fps <= 0) fps = 30;
                    long vDur = 1000000L / fps;
                    long aDur = useAudio ? 1000000L * 1024 / aSr : 0;
                    long vMax = 0, aMax = 0;
                    long lastReport = 0;

                    while (true) {
                        int t = ex.getSampleTrackIndex();
                        if (t < 0) break;
                        long sz = ex.getSampleSize();
                        if (sz > buf.capacity()) buf = ByteBuffer.allocate((int) sz);
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        long pts = ex.getSampleTime();
                        int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        info.set(0, n, pts + offset, flags);
                        if (t == vIdx) {
                            muxer.writeSampleData(outV, buf, info);
                            if (pts > vMax) vMax = pts;
                        } else if (useAudio && t == aIdx) {
                            muxer.writeSampleData(outA, buf, info);
                            if (pts > aMax) aMax = pts;
                        }
                        ex.advance();

                        if (durUs > 0 && pts - lastReport > 500000) {
                            lastReport = pts;
                            float f = (i + Math.min(1f, (float) pts / durUs)) / in.size();
                            progress(f, label);
                        }
                    }
                    long end = vMax + vDur;
                    if (useAudio) end = Math.max(end, aMax + aDur);
                    offset += end;
                } finally {
                    ex.release();
                    pfd.close();
                }
            }

            muxer.stop();
            started = false;
            ok = true;
            progress(1f, "Finalizando...");
        } finally {
            try {
                if (muxer != null) {
                    if (started) {
                        try {
                            muxer.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
            try {
                outPfd.close();
            } catch (Exception ignored) {
            }
            if (Build.VERSION.SDK_INT >= 29) {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(outUri, v, null, null);
                } else {
                    try {
                        cr.delete(outUri, null, null);
                    } catch (Exception ignored) {
                    }
                }
            } else if (!ok) {
                try {
                    new File(outUri.getPath()).delete();
                } catch (Exception ignored) {
                }
            }
        }
        resultUri = outUri;
    }
}
