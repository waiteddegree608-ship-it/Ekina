package com.ekina.domain.rag;

import java.io.InputStream;

/**
 * Domain interface for parsing unstructured and semi-structured documents.
 * Implemented at the infrastructure layer using iText, POI, and Jsoup.
 */
public interface DocumentParser {

    /**
     * Extracts text from a PDF input stream.
     */
    String parsePdf(InputStream inputStream);

    /**
     * Extracts text from a Microsoft Word (.docx) input stream.
     */
    String parseDocx(InputStream inputStream);

    /**
     * Extracts tabular/textual data from a Microsoft Excel (.xlsx) input stream.
     */
    String parseXlsx(InputStream inputStream);

    /**
     * Extracts and cleans HTML text content, stripping tags and formatting templates.
     */
    String parseHtml(String htmlContent);
}
