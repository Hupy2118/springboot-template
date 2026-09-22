package com.cmbchina.template.backend.maintenance;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Projects manifest-owned dependencies into the assembled workspace POM only. */
public final class PomDependencyCompiler {
    private PomDependencyCompiler() { }
    public static void apply(Path pom, Collection<MavenDependency> dependencies) { write(pom, transform(read(pom), dependencies, false)); }
    public static String normalizedPom(Path pom, Collection<MavenDependency> dependencies) { return xml(transform(read(pom), dependencies, true)); }
    public static void assertContributionsIntact(Path pom, Collection<MavenDependency> dependencies) {
        Document document = read(pom);
        for (MavenDependency dependency : dependencies) {
            List<Element> matches = matches(document, dependency.coordinate());
            if (matches.size() != 1 || !same(matches.get(0), dependency))
                throw new TemplateException("GENERATED_CONTRIBUTION_MODIFIED", "maven dependency " + dependency.coordinate());
        }
    }
    private static Document transform(Document document, Collection<MavenDependency> dependencies, boolean remove) {
        for (MavenDependency dependency : dependencies) {
            List<Element> matches = matches(document, dependency.coordinate());
            if (remove) { for (Element match : matches) match.getParentNode().removeChild(match); }
            else if (matches.isEmpty()) append(document, dependency);
            else if (!same(matches.get(0), dependency)) throw new TemplateException("MAVEN_DEPENDENCY_CONFLICT", dependency.coordinate());
        }
        stripWhitespace(document.getDocumentElement());
        return document;
    }
    private static void stripWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int index = children.getLength() - 1; index >= 0; index--) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) node.removeChild(child);
            else if (child.getNodeType() == Node.ELEMENT_NODE) stripWhitespace(child);
        }
    }
    private static void append(Document document, MavenDependency value) {
        Element dependencies = first(document.getDocumentElement(), "dependencies");
        if (dependencies == null) { dependencies = document.createElement("dependencies"); document.getDocumentElement().appendChild(dependencies); }
        Element dependency = document.createElement("dependency"); dependencies.appendChild(dependency);
        child(document, dependency, "groupId", value.groupId); child(document, dependency, "artifactId", value.artifactId);
        if (value.version != null) child(document, dependency, "version", value.version);
        if (value.scope != null) child(document, dependency, "scope", value.scope);
    }
    private static void child(Document doc, Element parent, String name, String value) { Element child=doc.createElement(name); child.setTextContent(value); parent.appendChild(child); }
    private static List<Element> matches(Document document, String coordinate) {
        List<Element> found = new ArrayList<Element>(); NodeList list=document.getElementsByTagName("dependency");
        for(int i=0;i<list.getLength();i++) { Element element=(Element) list.item(i); if(coordinate.equals(text(element,"groupId") + ":" + text(element,"artifactId"))) found.add(element); }
        return found;
    }
    private static boolean same(Element element, MavenDependency value) { return equals(text(element,"version"),value.version) && equals(text(element,"scope"),value.scope); }
    private static boolean equals(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static Element first(Element parent, String name) { NodeList list=parent.getElementsByTagName(name); return list.getLength()==0 ? null : (Element)list.item(0); }
    private static String text(Element element, String name) { Element child=first(element,name); return child == null ? null : child.getTextContent().trim(); }
    private static Document read(Path file) {
        try (InputStream input=Files.newInputStream(file)) { DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(false); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); return factory.newDocumentBuilder().parse(input); }
        catch(Exception e) { throw new TemplateException("POM_DEPENDENCY_COMPILATION_FAILED", e.getMessage()); }
    }
    private static void write(Path file, Document document) { try { Files.write(file, xml(document).getBytes("UTF-8")); } catch(Exception e) { throw new TemplateException("POM_DEPENDENCY_COMPILATION_FAILED", e.getMessage()); } }
    private static String xml(Document document) {
        try { Transformer transformer=TransformerFactory.newInstance().newTransformer(); transformer.setOutputProperty(OutputKeys.INDENT,"yes"); transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount","4"); transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8"); StringWriter writer=new StringWriter(); transformer.transform(new DOMSource(document),new StreamResult(writer)); return writer.toString(); }
        catch(Exception e) { throw new TemplateException("POM_DEPENDENCY_COMPILATION_FAILED", e.getMessage()); }
    }
}
