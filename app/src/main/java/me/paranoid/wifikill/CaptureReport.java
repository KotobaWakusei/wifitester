package me.paranoid.wifikill;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;

/**
 * Renders the pure {@link CaptureAnalyzer} outcome as localized user text.
 *
 * <p>Both workbenches drive the report from this single source: the frame
 * analysis screen shows it as a card, monitor mode appends it to the log.
 * Failures never surface as exceptions here — a capture that cannot be read
 * becomes a one-line explanation, matching how the rest of the app reports.</p>
 */
final class CaptureReport {

    /** Rows shown per list before the aggregated "… and more" suffix. */
    private static final int MAX_ROWS = 8;

    private CaptureReport() { }

    /** Parse + render in one worker-friendly call. */
    static String summarize(Context context, File capture) {
        CaptureAnalyzer.Analysis analysis;
        try (FileInputStream in = new FileInputStream(capture)) {
            analysis = CaptureAnalyzer.parse(in);
        } catch (Exception e) {
            return context.getString(R.string.analysis_read_failed,
                    e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
        }
        return render(context, analysis);
    }

    static String render(Context context, CaptureAnalyzer.Analysis analysis) {
        if (!analysis.headerValid) {
            return analysis.isPcapng
                    ? context.getString(R.string.analysis_is_pcapng)
                    : context.getString(R.string.analysis_bad_header);
        }
        if (analysis.unsupported) {
            return context.getString(R.string.analysis_unsupported_link, analysis.linkType);
        }

        StringBuilder out = new StringBuilder();
        out.append(context.getString(R.string.analysis_statistic,
                analysis.totalFrames, analysis.mgmtFrames,
                analysis.dataFrames, analysis.controlFrames));
        if (analysis.corruptTail) {
            out.append('\n').append(context.getString(R.string.analysis_tail_cut));
        }

        appendAccessPoints(context, out, analysis);
        appendHandshakes(context, out, analysis);
        return out.toString();
    }

    private static void appendAccessPoints(Context context, StringBuilder out,
                                           CaptureAnalyzer.Analysis analysis) {
        java.util.List<CaptureAnalyzer.AccessPoint> aps = analysis.accessPoints;
        if (aps.isEmpty()) return;
        out.append('\n').append('\n')
                .append(context.getString(R.string.analysis_ap_header, aps.size()));
        int shown = 0;
        for (CaptureAnalyzer.AccessPoint ap : aps) {
            if (shown == MAX_ROWS) {
                out.append('\n').append(context.getString(R.string.analysis_ap_more,
                        aps.size() - MAX_ROWS));
                break;
            }
            out.append('\n').append(context.getString(R.string.analysis_ap_row,
                    ap.bssid,
                    ap.ssid.isEmpty() ? context.getString(R.string.analysis_ssid_hidden) : ap.ssid,
                    ap.channel >= 0 ? String.valueOf(ap.channel)
                            : context.getString(R.string.analysis_unknown),
                    security(context, ap)));
            shown++;
        }
    }

    private static void appendHandshakes(Context context, StringBuilder out,
                                         CaptureAnalyzer.Analysis analysis) {
        java.util.List<CaptureAnalyzer.Handshake> hs = analysis.handshakes;
        out.append('\n').append('\n');
        if (hs.isEmpty()) {
            out.append(context.getString(R.string.analysis_hs_none));
        } else {
            out.append(context.getString(R.string.analysis_hs_header, hs.size()));
            int shown = 0;
            for (CaptureAnalyzer.Handshake handshake : hs) {
                if (shown == MAX_ROWS) {
                    out.append('\n').append(context.getString(R.string.analysis_hs_more,
                            hs.size() - MAX_ROWS));
                    break;
                }
                out.append('\n').append(context.getString(R.string.analysis_hs_row,
                        handshake.bssid, handshake.station,
                        handshake.m1, handshake.m2, handshake.m3, handshake.m4,
                        handshake.crackable()
                                ? context.getString(R.string.analysis_hs_ok)
                                : context.getString(R.string.analysis_hs_partial)));
                shown++;
            }
        }
        if (analysis.groupKeyFrames > 0) {
            out.append('\n').append(context.getString(R.string.analysis_hs_group,
                    analysis.groupKeyFrames));
        }
    }

    /** Localized security tags in a stable order, joined with " / ". */
    private static String security(Context context, CaptureAnalyzer.AccessPoint ap) {
        java.util.Set<CaptureAnalyzer.Security> set = ap.security;
        if (set.isEmpty()) return context.getString(R.string.analysis_unknown);

        StringBuilder out = new StringBuilder();
        joinIf(out, set.contains(CaptureAnalyzer.Security.WPA2),
                context.getString(R.string.analysis_security_wpa2));
        joinIf(out, set.contains(CaptureAnalyzer.Security.WPA3),
                context.getString(R.string.analysis_security_wpa3));
        joinIf(out, set.contains(CaptureAnalyzer.Security.WPA1),
                context.getString(R.string.analysis_security_wpa));
        joinIf(out, set.contains(CaptureAnalyzer.Security.WEP),
                context.getString(R.string.analysis_security_wep));
        joinIf(out, set.contains(CaptureAnalyzer.Security.OPEN),
                context.getString(R.string.analysis_security_open));
        return out.toString();
    }

    private static void joinIf(StringBuilder out, boolean condition, String text) {
        if (!condition) return;
        if (out.length() > 0) out.append(" / ");
        out.append(text);
    }
}
