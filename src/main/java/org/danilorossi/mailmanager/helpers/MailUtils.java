package org.danilorossi.mailmanager.helpers;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import lombok.Cleanup;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.java.Log;
import lombok.val;
import org.jsoup.Jsoup;

@Log
@UtilityClass
public final class MailUtils {

  static {
    LogConfigurator.configLog(log);
  }

  /** Hard cap to avoid unbounded memory when parsing pathological emails. */
  private static final int MAX_OUTPUT_CHARS = 200_000;

  public static String extractTextFromMessage(final Message msg) {
    val sb = new StringBuilder(4096);
    try {
      extractPart(msg, sb);
    } catch (Exception ex) {
      LangUtils.debug(log, "extractTextFromMessage failed softly", ex);
    }
    val out = trimToMax(sb.toString().trim(), MAX_OUTPUT_CHARS);
    return out;
  }

  private static void extractPart(final Part part, @NonNull final StringBuilder out)
      throws Exception {
    if (part == null) return;

    LangUtils.debug(
        log,
        "Estrazione contenuto da {}",
        LangUtils.nullToSomething(part.getDescription(), "(empty description)"));

    // Skip binary/inline resources (images, pdf, etc.)
    if (isSkippableBinary(part)) return;

    LangUtils.debug(
        log,
        "Rilevato MIME {}",
        LangUtils.nullToSomething(part.getContentType(), "(empty content-type)"));

    if (part.isMimeType("text/plain")) {
      val txt = getTextPayload(part);
      if (!LangUtils.empty(txt)) appendParagraph(out, cleanText(txt));
      return;
    }

    if (part.isMimeType("text/html")) {
      val html = getTextPayload(part);
      if (!LangUtils.empty(html)) appendParagraph(out, htmlToPlainText(html));
      return;
    }

    if (part.isMimeType("multipart/alternative")) {
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        val best = pickFromAlternative(mp);
        if (!LangUtils.empty(best)) appendParagraph(out, best);
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
            LangUtils.debug(log, "Skipping subpart on error", ex);
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
      if (!LangUtils.empty(txt)) appendParagraph(out, cleanText(txt));
    }

    LangUtils.warn(log, "MIME NON GESTITO!");
  }

  /* =================== Helpers =================== */

  private static void appendParagraph(@NonNull final StringBuilder out, final String text) {
    if (LangUtils.empty(text)) return;
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
            LangUtils.debug(log, "Unsupported charset '{}', falling back to UTF-8", cs);
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
      LangUtils.debug(log, "getTextPayload failed softly", ex);
    }
    return "";
  }

  private static String htmlToPlainText(final String html) {
    if (LangUtils.empty(html)) return "";
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
      val hasFileName = !LangUtils.empty(filename);
      val isTextish = p.isMimeType("text/*") || p.isMimeType("message/*");
      if (hasFileName && !isTextish) return true;

      // Content-ID usually marks inline resources (cid:), skip when not text
      val cids = p.getHeader("Content-ID");
      if (cids != null && cids.length > 0 && !isTextish) return true;

      try {
        if (!p.isMimeType("text/*") && p.isMimeType("image/*")) return true;
      } catch (MessagingException __) {
        /* ignore */
      }
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
    if (LangUtils.empty(raw)) return "";
    try {
      return cleanText(MimeUtility.decodeText(raw));
    } catch (Exception e) {
      // Fallback best-effort
      return cleanText(raw);
    }
  }

  /**
   * Reads a single CRLF-terminated line. Returns null on EOF before any byte is read. Strips the
   * trailing CRLF.
   */
  public static String readLineCRLF(@NonNull final InputStream in) throws IOException {
    @Cleanup val baos = new ByteArrayOutputStream(128);
    int prev = -1;
    int b;
    boolean gotAny = false;
    while ((b = in.read()) != -1) {
      gotAny = true;
      if (prev == '\r' && b == '\n') {
        // strip the last '\r'
        val arr = baos.toByteArray();
        val len = Math.max(0, arr.length - 1);
        return new String(arr, 0, len, StandardCharsets.US_ASCII);
      }
      baos.write(b);
      prev = b;
    }
    return gotAny ? new String(baos.toByteArray(), StandardCharsets.US_ASCII) : null;
  }

  /**
   * Converte un jakarta.mail.Message in RFC822 bytes (header + body) usando writeTo(). Usa CRLF
   * corrette e mantiene intatti gli header.
   */
  public static byte[] toRfc822Bytes(@NonNull final Message message) throws IOException {
    @Cleanup val baos = new ByteArrayOutputStream(64 * 1024);
    try {
      // writeTo() serializza l'intero messaggio (header + body) in formato RFC822
      message.writeTo(baos);
    } catch (MessagingException e) {
      // Riconfeziono in IOException per coerenza con le altre API I/O del client
      throw new IOException("Failed to serialize jakarta.mail.Message to RFC822", e);
    }
    return baos.toByteArray();
  }

  public static String safeSubject(@NonNull final Message m) {
    try {
      return String.valueOf(m.getSubject());
    } catch (MessagingException e) {
      return "(no-subject)";
    }
  }

  public static void ensureOpenRO(@NonNull final Folder f) throws MessagingException {
    if (!f.isOpen()) f.open(Folder.READ_ONLY);
  }

  public static void ensureOpenRW(@NonNull final Folder f) throws MessagingException {
    if (!f.isOpen() || f.getMode() != Folder.READ_WRITE) f.open(Folder.READ_WRITE);
  }

  public static Folder ensureFolderExistsAndOpen(
      @NonNull final Store store, @NonNull final String folderName) throws MessagingException {
    val f = store.getFolder(folderName);
    if (!f.exists()) {
      if (!f.create(Folder.HOLDS_MESSAGES)) {
        throw new MessagingException("Impossibile creare la cartella: " + folderName);
      }
    }
    ensureOpenRW(f);
    return f;
  }

  // Prova a indovinare la cartella “Archivio” se non specificata
  public static String resolveArchiveName(Store store) throws MessagingException {
    // Nomi comuni
    val candidates =
        new String[] {"Archive", "Archivio", "[Gmail]/All Mail", "[Gmail]/Tutti i messaggi"};
    for (val c : candidates) {
      val f = store.getFolder(c);
      if (f != null && f.exists()) return c;
    }
    return null; // nessun match
  }

  // Split "label1,label2 ; label3" -> ["label1","label2","label3"]
  public static String[] splitLabels(final String s) {
    return s == null
        ? new String[0]
        : java.util.Arrays.stream(s.split("[,;]"))
            .map(String::trim)
            .filter(x -> !x.isEmpty())
            .toArray(String[]::new);
  }

  // MailUtils.java
  public static MimeMessage buildForward(
      @NonNull final Session session, @NonNull final Message original, @NonNull final String to)
      throws MessagingException, IOException {
    val fwd = new MimeMessage(session);
    // From: prova a riusare il mittente originale o lascia vuoto (dipende dal tuo SMTP)
    Address[] from = original.getReplyTo();
    if (from == null || from.length == 0) from = original.getFrom();
    if (from != null && from.length > 0) fwd.setFrom(from[0]);

    fwd.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
    val subj = original.getSubject();
    fwd.setSubject((subj == null || subj.isBlank()) ? "Fwd:" : "Fwd: " + subj, "UTF-8");

    // Allego l’originale come message/rfc822
    val raw = MailUtils.toRfc822Bytes(original);
    val bds = new jakarta.mail.util.ByteArrayDataSource(raw, "message/rfc822");
    val attachment = new MimeBodyPart();
    attachment.setDataHandler(new DataHandler(bds));
    attachment.setFileName("forwarded.eml");

    val intro = new MimeBodyPart();
    intro.setText("Inoltro messaggio:", "UTF-8");

    val mp = new MimeMultipart();
    mp.addBodyPart(intro);
    mp.addBodyPart(attachment);

    fwd.setHeader("Auto-Submitted", "auto-forwarded");
    fwd.setContent(mp);
    fwd.saveChanges();
    return fwd;
  }
}
