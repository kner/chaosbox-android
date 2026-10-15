package local.sshcopy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.json.*;

/** Shared desktop XMP schema; unrelated XMP properties are retained. */
final class XmpRecords {
    static final String NS = "urn:chaosbox:metadata:1.0/";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final String XMP = "http://ns.adobe.com/xap/1.0/";
    private static final byte[] HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final String[] FIELDS = {"box", "anzahl", "device", "alias", "category", "package", "comment", "created", "id", "revision", "base_revision"};
    private static String ns(String field) { return field.equals("comment") ? DC : field.equals("created") ? XMP : NS; }
    private static String name(String field) { return field.equals("comment") ? "description" : field.equals("created") ? "CreateDate" : field; }

    private static Document document(byte[] packet) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        for (byte b : packet) if (b == 0) throw new IOException("XMP must use UTF-8 encoding");
        String xml = new String(packet, StandardCharsets.UTF_8);
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IOException("XMP document types and entities are unsupported");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(packet));
    }
    private static byte[] packet(byte[] jpeg) throws IOException {
        if (jpeg.length < 2 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216) return null;
        for (int p = 2; p + 4 <= jpeg.length;) {
            int marker = jpeg[p+1] & 255;
            if (marker == 218 || marker == 217) break;
            int size = ((jpeg[p+2]&255)<<8) | (jpeg[p+3]&255);
            if (size < 2 || p+2+size > jpeg.length) throw new IOException("Invalid JPEG segment");
            if (marker == 225 && size >= HEADER.length+2 && Arrays.equals(HEADER, Arrays.copyOfRange(jpeg,p+4,p+4+HEADER.length)))
                return Arrays.copyOfRange(jpeg,p+4+HEADER.length,p+2+size);
            p += 2+size;
        }
        return null;
    }
    static String read(byte[] jpeg, String legacy) throws IOException {
        byte[] packet = packet(jpeg);
        if (packet == null) return legacy;
        try {
            Document d = document(packet);
            boolean complete = has(d, NS, "version");
            JSONObject record;
            try { record = complete ? new JSONObject() : new JSONObject(legacy); }
            catch (JSONException e) { record = new JSONObject(); record.put("comment", legacy); }
            boolean found = complete;
            for (String field : FIELDS) {
                if (!has(d, ns(field), name(field))) continue;
                found = true;
                String value = value(d, ns(field), name(field));
                if (field.equals("comment")) {
                    NodeList items = d.getElementsByTagNameNS(DC, "description");
                    if (items.getLength() > 0) {
                        NodeList alternatives = ((Element)items.item(0)).getElementsByTagNameNS(RDF, "li");
                        value = "";
                        for (int i=0; i<alternatives.getLength(); i++) {
                            Element item = (Element) alternatives.item(i);
                            if (item.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang").equals("x-default") || (!complete && i==0)) value=item.getTextContent();
                        }
                    }
                }
                if (field.equals("category")) {
                    java.util.List<String> aliases = new ArrayList<>();
                    java.util.Iterator<String> keys = record.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (key.equalsIgnoreCase("category") || key.equalsIgnoreCase("kategorie")) aliases.add(key);
                    }
                    for (String key : aliases) record.remove(key);
                }
                record.put(field, value);
                if (field.equals("anzahl")) { record.remove("count"); record.remove("Anzahl"); }
                if (field.equals("package")) record.remove("pack");
            }
            return found ? record.toString() : legacy;
        } catch (Exception e) { throw new IOException("Cannot read XMP metadata", e); }
    }
    private static boolean has(Document d, String ns, String key) {
        if (d.getElementsByTagNameNS(ns,key).getLength()>0) return true;
        NodeList all=d.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++) if(((Element)all.item(i)).hasAttributeNS(ns,key)) return true;
        return false;
    }
    private static String value(Document d,String ns,String key) {
        NodeList nodes=d.getElementsByTagNameNS(ns,key);
        if(nodes.getLength()>0) return nodes.item(0).getTextContent();
        NodeList all=d.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++) if(((Element)all.item(i)).hasAttributeNS(ns,key)) return ((Element)all.item(i)).getAttributeNS(ns,key);
        return "";
    }
    static byte[] write(byte[] jpeg, String json) throws IOException {
        return write(jpeg, json, jpeg);
    }
    static byte[] write(byte[] jpeg, String json, byte[] original) throws IOException {
        try {
            byte[] old=packet(original);
            Document d=document(old == null ? ("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='"+RDF+"'><rdf:Description rdf:about=''/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8) : old);
            Element description=(Element)d.getElementsByTagNameNS(RDF,"Description").item(0);
            if(description==null) throw new IOException("Missing XMP RDF description");
            JSONObject record=new JSONObject(json);
            for(String field : FIELDS) {
                if ((field.equals("id") || field.equals("revision") || field.equals("base_revision")) && !record.has(field)) continue;
                remove(d,ns(field),name(field));
                String prefix=field.equals("comment")?"dc":field.equals("created")?"xmp":"chaosbox";
                Element property=d.createElementNS(ns(field),prefix+":"+name(field));
                String text=record.isNull(field)?"":record.optString(field,"");
                if(field.equals("comment")) {
                    Element alt=d.createElementNS(RDF,"rdf:Alt"), li=d.createElementNS(RDF,"rdf:li");
                    li.setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:lang","x-default");
                    li.setTextContent(text); alt.appendChild(li); property.appendChild(alt);
                } else property.setTextContent(text);
                description.appendChild(property);
            }
            remove(d,NS,"version");
            remove(d,"http://ns.adobe.com/exif/1.0/","UserComment");
            description.setAttributeNS(NS,"chaosbox:version","1");
            ByteArrayOutputStream xml=new ByteArrayOutputStream();
            Transformer t=TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
            t.transform(new DOMSource(d),new StreamResult(xml));
            int size=2+HEADER.length+xml.size();
            if(size>65535) throw new IOException("XMP metadata is too large");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write(jpeg,0,2); out.write(255); out.write(225); out.write(size>>8); out.write(size&255); out.write(HEADER); xml.writeTo(out);
            int p=2;
            while(p+4<=jpeg.length) {
                int marker=jpeg[p+1]&255;
                if(marker==218 || marker==217) break;
                int length=((jpeg[p+2]&255)<<8)|(jpeg[p+3]&255);
                if(length<2 || p+2+length>jpeg.length) throw new IOException("Invalid JPEG segment");
                boolean xmp=marker==225 && length>=HEADER.length+2 && Arrays.equals(HEADER,Arrays.copyOfRange(jpeg,p+4,p+4+HEADER.length));
                if(!xmp) out.write(jpeg,p,length+2);
                p+=length+2;
            }
            out.write(jpeg,p,jpeg.length-p);
            return out.toByteArray();
        } catch(IOException e) { throw e; } catch(Exception e) { throw new IOException("Cannot write XMP metadata",e); }
    }
    private static void remove(Document d,String ns,String key) {
        NodeList nodes=d.getElementsByTagNameNS(ns,key);
        // Android may return a snapshot NodeList: removing a node does not
        // necessarily remove it from the query result. Visit each match once.
        for(int i=nodes.getLength()-1;i>=0;i--) {
            Node n=nodes.item(i);
            Node parent=n.getParentNode();
            if(parent!=null) parent.removeChild(n);
        }
        NodeList all=d.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++) ((Element)all.item(i)).removeAttributeNS(ns,key);
    }
}
