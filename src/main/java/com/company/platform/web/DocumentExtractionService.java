/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/** Extracts native document text for the platform knowledge pipeline. OCR is deliberately excluded. */
@Component
public class DocumentExtractionService {

    private static final int MAX_EXTRACTED_CHARACTERS = 5_000_000;
    private static final int CHUNK_SIZE = 1_200;
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "pdf",
                    "doc",
                    "docx",
                    "xls",
                    "xlsx",
                    "ppt",
                    "pptx",
                    "md",
                    "markdown",
                    "txt",
                    "csv");

    public boolean supports(String filename) {
        return SUPPORTED_EXTENSIONS.contains(extension(filename));
    }

    public Extraction extract(Path file, String filename) {
        String extension = extension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported document type: " + extension);
        }
        try (InputStream input = Files.newInputStream(file)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
            Metadata metadata = new Metadata();
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
            String text = normalize(handler.toString());
            List<String> chunks = chunks(text);
            return new Extraction(
                    documentType(extension),
                    text,
                    chunks,
                    text.isBlank() ? "requires_ocr" : "parsed",
                    text.isBlank()
                            ? "No native text was found. OCR can be enabled for scanned documents."
                            : "");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract document content: " + filename, e);
        }
    }

    private static List<String> chunks(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\n+")) {
            String value = paragraph.strip();
            if (value.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + value.length() + 2 > CHUNK_SIZE) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (value.length() > CHUNK_SIZE) {
                for (int start = 0; start < value.length(); start += CHUNK_SIZE) {
                    int end = Math.min(value.length(), start + CHUNK_SIZE);
                    if (current.length() > 0) {
                        chunks.add(current.toString());
                        current.setLength(0);
                    }
                    chunks.add(value.substring(start, end));
                }
            } else {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(value);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return List.copyOf(new LinkedHashSet<>(chunks));
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replace('\u0000', ' ').replaceAll("[ \\t]+\\n", "\\n").strip();
    }

    private static String extension(String filename) {
        String name = filename == null ? "" : filename.strip();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String documentType(String extension) {
        return switch (extension) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "xls", "xlsx" -> "excel";
            case "ppt", "pptx" -> "powerpoint";
            case "md", "markdown" -> "markdown";
            case "csv" -> "csv";
            case "txt" -> "text";
            default -> "file";
        };
    }

    public record Extraction(
            String documentType,
            String text,
            List<String> chunks,
            String parseStatus,
            String message) {}
}
