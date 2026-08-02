package com.tarun.ledgerguard.settlement;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Bounded file reading, exact file hashing, and orchestration of parsing
 * and validation. Delegates the actual transactional persistence to
 * {@link SettlementImportProcessor} -- this class opens no database
 * transaction itself.
 *
 * <p>Never writes the uploaded file to any application-controlled path;
 * the only filesystem interaction is {@link MultipartFile#getInputStream()},
 * a framework-managed temporary resource this class only ever reads from,
 * never paths into using the submitted filename (see
 * {@link #sanitizeFilename}, which never touches the filesystem).
 */
@Service
public class SettlementImportService {

	// Multipart clients vary widely in what they set for a CSV part's
	// content type (or omit it entirely) -- a null content type is
	// allowed; a present-but-wrong one (e.g. an image or JSON type) is
	// rejected.
	private static final Set<String> ALLOWED_CONTENT_TYPES =
			Set.of("text/csv", "application/csv", "text/plain", "application/octet-stream");

	private static final int MAX_ORIGINAL_FILENAME_LENGTH = 255;
	private static final int READ_BUFFER_SIZE = 8192;

	private final SettlementImportProperties properties;
	private final SettlementCsvParser parser;
	private final SettlementImportProcessor processor;

	public SettlementImportService(SettlementImportProperties properties, SettlementCsvParser parser,
			SettlementImportProcessor processor) {
		this.properties = properties;
		this.parser = parser;
		this.processor = processor;
	}

	public SettlementImportOutcome importFile(String rawSource, MultipartFile file) {
		if (!properties.isEnabled()) {
			throw new SettlementImportDisabledException();
		}
		if (file == null || file.isEmpty()) {
			throw new InvalidSettlementRequestException("file: must not be empty");
		}
		String contentType = file.getContentType();
		if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
			throw new UnsupportedSettlementContentTypeException();
		}
		if (rawSource == null || rawSource.isBlank()) {
			throw new InvalidSettlementRequestException("source: must not be blank");
		}
		String trimmedSource = rawSource.trim();
		if (trimmedSource.length() > properties.getMaxSourceLength()) {
			throw new InvalidSettlementRequestException(
					"source: exceeds maximum length of " + properties.getMaxSourceLength());
		}

		byte[] rawBytes = readBounded(file, properties.getMaxFileSizeBytes());
		String fileHash = Sha256.hex(rawBytes);
		String normalizedSource = SourceNormalizer.normalize(trimmedSource);
		String originalFilename = sanitizeFilename(file.getOriginalFilename());

		List<SettlementCsvRow> rows = parser.parse(rawBytes, normalizedSource, properties);

		return processor.importFile(trimmedSource, normalizedSource, originalFilename, fileHash,
				(long) rawBytes.length, rows);
	}

	/**
	 * Reads the upload fully into memory, bounded at
	 * {@code maxBytes} regardless of what {@link MultipartFile#getSize()}
	 * reports -- a defensive check independent of that metadata, and of
	 * Spring's own outer {@code spring.servlet.multipart.max-file-size}
	 * boundary.
	 */
	private byte[] readBounded(MultipartFile file, long maxBytes) {
		try (InputStream in = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[READ_BUFFER_SIZE];
			long total = 0;
			int read;
			while ((read = in.read(chunk)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new SettlementFileTooLargeException(maxBytes);
				}
				buffer.write(chunk, 0, read);
			}
			if (total == 0) {
				throw new InvalidSettlementRequestException("file: must not be empty");
			}
			return buffer.toByteArray();
		} catch (IOException e) {
			throw new InvalidSettlementRequestException("file: could not be read");
		}
	}

	/**
	 * Sanitized basename only, never used for a filesystem path. Strips
	 * any directory components the client may have supplied (whether
	 * '/'- or '\'-separated) and truncates to a conservative length;
	 * {@code null}/blank is preserved as {@code null} rather than an
	 * empty string.
	 */
	private String sanitizeFilename(String original) {
		if (original == null) {
			return null;
		}
		String basename = original.replace('\\', '/');
		int lastSlash = basename.lastIndexOf('/');
		if (lastSlash >= 0) {
			basename = basename.substring(lastSlash + 1);
		}
		basename = basename.trim();
		if (basename.isEmpty()) {
			return null;
		}
		if (basename.length() > MAX_ORIGINAL_FILENAME_LENGTH) {
			basename = basename.substring(0, MAX_ORIGINAL_FILENAME_LENGTH);
		}
		return basename;
	}

}
