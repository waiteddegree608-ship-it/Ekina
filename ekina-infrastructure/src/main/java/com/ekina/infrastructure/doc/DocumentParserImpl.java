package com.ekina.infrastructure.doc;

import com.ekina.domain.rag.DocumentParser;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Infrastructure implementation of DocumentParser using Jsoup, Apache POI, and iText 8.
 */
@Service
public class DocumentParserImpl implements DocumentParser {

    @Override
    public String parsePdf(InputStream inputStream) {
        StringBuilder text = new StringBuilder();
        try (PdfReader reader = new PdfReader(inputStream);
             PdfDocument pdfDoc = new PdfDocument(reader)) {
            int numberOfPages = pdfDoc.getNumberOfPages();
            for (int i = 1; i <= numberOfPages; i++) {
                text.append(PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i), new SimpleTextExtractionStrategy()));
                text.append("\n");
            }
        } catch (Exception e) {
            System.err.println("Failed to parse PDF using iText: " + e.getMessage());
        }
        return text.toString();
    }

    @Override
    public String parseDocx(InputStream inputStream) {
        try (XWPFDocument doc = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            System.err.println("Failed to parse DOCX using Apache POI: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String parseXlsx(InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(getCellValue(cell)).append("\t");
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse XLSX using Apache POI: " + e.getMessage());
        }
        return sb.toString();
    }

    @Override
    public String parseHtml(String htmlContent) {
        if (htmlContent == null) {
            return "";
        }
        return Jsoup.parse(htmlContent).text();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }
}
