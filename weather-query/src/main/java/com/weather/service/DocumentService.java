package com.weather.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class DocumentService {
    // ==================== 解析文档（用户发来 → 提取文字）====================

    /**
     * 根据文件名后缀自动选择解析方式
     */
    public String parse(byte[] data, String fileName) throws Exception {
        String ext = getExtension(fileName).toLowerCase();
        return switch (ext) {
            case "txt", "md", "csv", "json", "xml", "log" ->
                    parseText(data);
            case "pdf" ->
                    parsePdf(data);
            case "docx", "doc" ->
                    parseWord(data);
            case "xlsx", "xls" ->
                    parseExcel(data);
            default ->
                    "不支持的文件格式: " + ext + "，目前支持: txt/md/csv/pdf/docx/xlsx";
        };
    }

    /** TXT/MD/CSV 等纯文本 */
    private String parseText(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    /** PDF → 文字 */
    private String parsePdf(byte[] data) throws Exception {
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(data))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /** Word(.docx) → 文字 */
    private String parseWord(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            for (XWPFParagraph para : doc.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }
            // 也提取表格内容
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        sb.append(cell.getText()).append("\t");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** Excel(.xlsx) → 文字（带行列信息）*/
    private String parseExcel(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(getCellValue(cell)).append("\t");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    // ==================== 生成文档（AI 内容 → 写成文件）====================

    /**
     * 生成文档，返回文件字节
     * @param content  文档内容
     * @param format   格式: txt / md / csv / pdf / docx / xlsx
     * @return         文件字节数组
     */
    public byte[] generate(String content, String format) throws Exception {
        format = format.toLowerCase();
        return switch (format) {
            case "txt", "md", "csv" ->
                    content.getBytes(StandardCharsets.UTF_8);
            case "pdf" ->
                    generatePdf(content);
            case "docx" ->
                    generateWord(content);
            case "xlsx" ->
                    generateExcel(content);
            default ->
                    content.getBytes(StandardCharsets.UTF_8); // 默认txt
        };
    }

    /** 生成 PDF */
    private byte[] generatePdf(String content) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.setLeading(14.5f);
                cs.newLineAtOffset(50, 700);
                for (String line : content.split("\n")) {
                    cs.showText(line.length() > 80 ? line.substring(0, 80) : line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 生成 Word(.docx) */
    private byte[] generateWord(String content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : content.split("\n")) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
                run.setFontSize(12);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** 生成 Excel(.xlsx) — 每行按 tab 或逗号分列 */
    private byte[] generateExcel(String content) throws Exception {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Row row = sheet.createRow(i);
                String[] cells = lines[i].split("\t|,");
                for (int j = 0; j < cells.length; j++) {
                    row.createCell(j).setCellValue(cells[j].trim());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 获取文件扩展名 */
    private String getExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx + 1) : "";
    }

    /** 根据格式返回 MIME 类型（sendFile 用）*/
    public static String getMimeType(String format) {
        return switch (format.toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv" -> "text/csv";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }
}
