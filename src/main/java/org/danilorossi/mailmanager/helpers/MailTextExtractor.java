package org.danilorossi.mailmanager.helpers;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.logging.Logger;
import lombok.Cleanup;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.jsoup.Jsoup;

@UtilityClass
public final class MailTextExtractor {

  private static final Logger LOG = Logger.getLogger(MailTextExtractor.class.getName());

  static {
    LogConfigurator.configLog(LOG);
  }

  /** Hard cap to avoid unbounded memory when parsing pathological emails. */
  private static final int MAX_OUTPUT_CHARS = 200_000;

  public static String extractTextFromMessage(final Message msg) {
    val sb = new StringBuilder(4096);
    try {
      extractPart(msg, sb);
    } catch (Exception ex) {
      LangUtils.debug(LOG, "extractTextFromMessage failed softly", ex);
    }
    val out = trimToMax(sb.toString().trim(), MAX_OUTPUT_CHARS);
    return out;
  }

  private static void extractPart(final Part part, @NonNull final StringBuilder out)
      throws Exception {
    if (part == null) return;

    // Skip binary/inline resources (images, pdf, etc.)
    if (isSkippableBinary(part)) return;

    if (part.isMimeType("text/plain")) {
      val txt = getTextPayload(part);
      if (!LangUtils.emptyString(txt)) appendParagraph(out, cleanText(txt));
      return;
    }

    if (part.isMimeType("text/html")) {
      val html = getTextPayload(part);
      if (!LangUtils.emptyString(html)) appendParagraph(out, htmlToPlainText(html));
      return;
    }

    if (part.isMimeType("multipart/alternative")) {
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        val best = pickFromAlternative(mp);
        if (!LangUtils.emptyString(best)) appendParagraph(out, best);
      }
      return;
    }

    if (part.isMimeType("multipart/related")) {
      // Usually HTML + inline images. Extract the best textual child.
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        for (int i = 0; i < mp.getCount(); i++) {
          val bp = mp.getBodyPart(i);
          if (bp.isMimeType("text/html") || bp.isMimeType("text/plain")) {
            extractPart(bp, out);
            break; // first textual wins
          }
        }
      }
      return;
    }

    if (part.isMimeType("multipart/*")) {
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        for (int i = 0; i < mp.getCount(); i++) {
          try {
            extractPart(mp.getBodyPart(i), out);
          } catch (Exception ex) {
            LangUtils.debug(LOG, "Skipping subpart on error", ex);
          }
        }
      }
      return;
    }

    if (part.isMimeType("message/rfc822")) {
      val content = part.getContent();
      if (content instanceof Message nested) {
        extractPart(nested, out);
      }
      return;
    }

    // Fallback for rare textual types
    if (part.isMimeType("text/*")) {
      val txt = getTextPayload(part);
      if (!LangUtils.emptyString(txt)) appendParagraph(out, cleanText(txt));
    }
  }

  /* =================== Helpers =================== */

  private static void appendParagraph(StringBuilder out, String text) {
    if (LangUtils.emptyString(text)) return;
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    out.append(text).append("\n\n");
  }

  private static String cleanText(final String text) {
    if (text == null) return "";
    // Collapse NBSP and excessive whitespace/newlines
    return text.replace('\u00A0', ' ')
        .replaceAll("\\R+", "\n")
        .replaceAll("[\\t\\x0B\\f ]{2,}", " ")
        .trim();
  }

  private static Charset detectCharset(@NonNull final Part part) {
    try {
      val ct = part.getContentType();
      if (ct != null) {
        val contentType = new ContentType(ct);
        val cs = contentType.getParameter("charset");
        if (cs != null) {
          try {
            // Normalize common weird labels (e.g., utf8, cp-1252, etc.)
            String norm = cs.trim().toLowerCase(Locale.ROOT).replace("_", "-");
            if ("utf8".equals(norm)) norm = "utf-8";
            return Charset.forName(norm);
          } catch (IllegalCharsetNameException | UnsupportedCharsetException __) {
            LangUtils.debug(LOG, "Unsupported charset '{}', falling back to UTF-8", cs);
          }
        }
      }
    } catch (MessagingException __) {
    }
    return StandardCharsets.UTF_8;
  }

  private static String getTextPayload(@NonNull final Part part) {
    try {
      val content = part.getContent(); // Jakarta Mail decodes transfer-encoding (QP/Base64)
      if (content instanceof String s) return s;

      @Cleanup val is = part.getInputStream();
      return readAll(is, detectCharset(part));
    } catch (Exception ex) {
      LangUtils.debug(LOG, "getTextPayload failed softly", ex);
    }
    return "";
  }

  private static String htmlToPlainText(final String html) {
    if (LangUtils.emptyString(html)) return "";
    val doc = Jsoup.parse(html);
    // Preserve logical breaks before extracting text
    doc.select("br").append("\\n");
    doc.select("p, li, div, tr, h1, h2, h3, h4, h5, h6").prepend("\\n");
    val text = doc.text().replace("\\n", "\n");
    return cleanText(text);
  }

  /**
   * Heuristic: in multipart/alternative the *last* part is usually the richest. We scan from last
   * to first preferring text/html, then text/plain, then any text/*.
   */
  private static String pickFromAlternative(@NonNull final Multipart mp) throws Exception {
    // Prefer text/html scanning from last to first
    for (int i = mp.getCount() - 1; i >= 0; i--) {
      val bp = mp.getBodyPart(i);
      if (!isSkippableBinary(bp) && bp.isMimeType("text/html")) {
        return htmlToPlainText(getTextPayload(bp));
      }
    }
    // Then text/plain
    for (int i = mp.getCount() - 1; i >= 0; i--) {
      val bp = mp.getBodyPart(i);
      if (!isSkippableBinary(bp) && bp.isMimeType("text/plain")) {
        return cleanText(getTextPayload(bp));
      }
    }
    // Finally any text/*
    for (int i = mp.getCount() - 1; i >= 0; i--) {
      val bp = mp.getBodyPart(i);
      if (!isSkippableBinary(bp) && bp.isMimeType("text/*")) {
        return cleanText(getTextPayload(bp));
      }
    }
    return "";
  }

  /** Decide if a part is a non-text resource we want to skip (attachments, inline images, etc.). */
  private static boolean isSkippableBinary(@NonNull final Part p) {
    try {
      // Explicit attachments
      val disp = p.getDisposition();
      if (disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT)) return true;

      // Inline with filename & non-text content -> likely an embedded resource (image/pdf/etc.)
      val filename = p.getFileName();
      val hasFileName = !LangUtils.emptyString(filename);
      val isTextish = p.isMimeType("text/*") || p.isMimeType("message/*");
      if (hasFileName && !isTextish) return true;

      // Content-ID usually marks inline resources (cid:), skip when not text
      val cids = p.getHeader("Content-ID");
      if (cids != null && cids.length > 0 && !isTextish) return true;

      return false;
    } catch (MessagingException __) {
      return false;
    }
  }

  private static String readAll(@NonNull final InputStream is, @NonNull final Charset cs)
      throws IOException {
    @Cleanup val bos = new ByteArrayOutputStream();
    val buf = new byte[8192];
    int r;
    while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
    return bos.toString(cs);
  }

  private static String trimToMax(final String s, final int max) {
    if (s == null || s.length() <= max) return s == null ? "" : s;
    return s.substring(0, max);
  }

  /** Decodifica un subject RFC 2047 in UTF-8, safe. */
  public static String decodeSubjectSafe(final String raw) {
    if (LangUtils.emptyString(raw)) return "";
    try {
      return cleanText(MimeUtility.decodeText(raw));
    } catch (Exception e) {
      // Fallback best-effort
      return cleanText(raw);
    }
  }
}
