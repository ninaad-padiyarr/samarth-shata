import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

final class Workbook {
    private static final String SPREADSHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    static List<List<String>> read(byte[] bytes) throws Exception {
        byte[] sheet = null, strings = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries>100) throw new IOException("Too many workbook entries");
                byte[] data = zip.readNBytes(1_000_001);
                if (data.length>1_000_000) throw new IOException("Workbook entry too large");
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) sheet=data;
                if (entry.getName().equals("xl/sharedStrings.xml")) strings=data;
            }
        }
        if (sheet==null) throw new IOException("First worksheet missing");
        List<String> shared = new ArrayList<>();
        if (strings!=null) {
            NodeList items = parse(strings).getElementsByTagNameNS(SPREADSHEET,"si");
            for (int i=0;i<items.getLength();i++) shared.add(text((Element)items.item(i),"t"));
        }
        NodeList rows = parse(sheet).getElementsByTagNameNS(SPREADSHEET,"row");
        if (rows.getLength()>501) throw new IOException("Too many worksheet rows");
        List<List<String>> result = new ArrayList<>();
        for (int i=0;i<rows.getLength();i++) {
            var values = new ArrayList<>(Collections.nCopies(11,""));
            NodeList cells = ((Element)rows.item(i)).getElementsByTagNameNS(SPREADSHEET,"c");
            for (int j=0;j<cells.getLength();j++) {
                Element cell = (Element)cells.item(j);
                String ref = cell.getAttribute("r");
                if (!ref.matches("[A-K][1-9][0-9]*")) {
                    if (text(cell,"v").isEmpty() && text(cell,"t").isEmpty()) continue;
                    throw new IOException("Unexpected worksheet column");
                }
                if (cell.getElementsByTagNameNS(SPREADSHEET,"f").getLength()>0)
                    throw new IOException("Formulas are not supported in imports");
                String value = text(cell,"v");
                switch (cell.getAttribute("t")) {
                    case "inlineStr" -> value = text(cell,"t");
                    case "s" -> value = shared.get(Integer.parseInt(value));
                    default -> { }
                }
                values.set(ref.charAt(0)-'A',value);
            }
            result.add(values);
        }
        return result;
    }

    private static Document parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String text(Element element,String tag) {
        var nodes = element.getElementsByTagNameNS(SPREADSHEET,tag);
        StringBuilder value = new StringBuilder();
        for (int i=0;i<nodes.getLength();i++) value.append(nodes.item(i).getTextContent());
        return value.toString();
    }

    static byte[] write(List<List<String>> rows) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            entry(zip,"[Content_Types].xml","""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
            String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
            String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";
            entry(zip,"_rels/.rels",relationships+"<Relationship Id=\"rId1\" Type=\""+rel+"officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/_rels/workbook.xml.rels",relationships+"<Relationship Id=\"rId1\" Type=\""+rel+"worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\""+SPREADSHEET+"\" xmlns:r=\""+rel.substring(0,rel.length()-1)+
                "\"><sheets><sheet name=\"CampusExam\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            StringBuilder xml = new StringBuilder("<worksheet xmlns=\""+SPREADSHEET+"\"><sheetData>");
            for (int i=0;i<rows.size();i++) {
                int row = i+1;
                xml.append("<row r=\"").append(row).append("\">");
                for (int col=0;col<rows.get(i).size();col++) {
                    xml.append("<c t=\"inlineStr\" r=\"").append((char)('A'+col)).append(row)
                        .append("\"><is><t xml:space=\"preserve\">").append(escape(rows.get(i).get(col))).append("</t></is></c>");
                }
                xml.append("</row>");
            }
            entry(zip,"xl/worksheets/sheet1.xml",xml.append("</sheetData></worksheet>").toString());
        }
        return buffer.toByteArray();
    }

    private static String escape(String text) {
        return text.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]","")
            .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static void entry(ZipOutputStream zip,String name,String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
