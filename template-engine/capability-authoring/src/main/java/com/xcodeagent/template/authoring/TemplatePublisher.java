package com.xcodeagent.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.CapabilityV2Loader;
import com.xcodeagent.template.engine.source.TemplateSourceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Publishes a verified draft by atomically committing a new revision and its digest. */
public final class TemplatePublisher {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    @SuppressWarnings("unchecked") public void publish(Path rawRoot, String revision, Path expectedProject, String capabilityId, PublicationGate gate) {
        if (gate == null) throw new TemplateSourceException("PUBLICATION_GATES_REQUIRED");
        Path root = rawRoot.toAbsolutePath().normalize(), staging;
        try { staging = Files.createTempDirectory(root.getParent(), ".template-publish-"); copy(root, staging); }
        catch (IOException e) { throw new TemplateSourceException("PUBLISH_FAILED: " + e.getMessage()); }
        try {
            String previous = new String(Files.readAllBytes(staging.resolve("template-revision.txt")), StandardCharsets.UTF_8).trim();
            if (revision == null || revision.trim().isEmpty() || revision.equals(previous)) throw new TemplateSourceException("TEMPLATE_REVISION_INVALID");
            new DraftTemplateSourceValidator().validate(staging);
            new RoundTripVerifier().verify(staging, expectedProject, capabilityId);
            gate.verifyExecutorEquivalence(staging); gate.verifyValidators(staging);
            Files.write(staging.resolve("template-revision.txt"), (revision + "\n").getBytes(StandardCharsets.UTF_8));
            Map<String,Object> manifest = yaml.readValue(staging.resolve("release-digests.yaml").toFile(), Map.class);
            ((Map<String,Object>) manifest.get("releases")).put(revision, digest(staging));
            yaml.writeValue(staging.resolve("release-digests.yaml").toFile(), manifest);
            new CapabilityV2Loader().load(staging);
            replace(root, staging);
        } catch (IOException e) { delete(staging); throw new TemplateSourceException("PUBLISH_FAILED: " + e.getMessage()); }
        catch (RuntimeException e) { delete(staging); throw e; }
    }
    private static String digest(Path root) { try { MessageDigest d = MessageDigest.getInstance("SHA-256"); List<Path> files = new ArrayList<Path>(); java.util.stream.Stream<Path> s = Files.walk(root); try { s.forEach(p -> { if (Files.isRegularFile(p)) files.add(p); }); } finally { s.close(); } Collections.sort(files); for (Path p : files) { String r=root.relativize(p).toString().replace('\\','/'); if ("release-digests.yaml".equals(r)) continue; d.update(r.getBytes(StandardCharsets.UTF_8)); d.update((byte)0); d.update(Files.readAllBytes(p)); d.update((byte)0); } StringBuilder b=new StringBuilder(); for(byte x:d.digest()) b.append(String.format("%02x",x&255)); return b.toString(); } catch(Exception e) { throw new TemplateSourceException("PUBLISH_FAILED: digest"); } }
    private static void copy(Path from, Path to) throws IOException { java.util.stream.Stream<Path> s=Files.walk(from); try{s.forEach(p->{try{Path t=to.resolve(from.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(t);else Files.copy(p,t);}catch(IOException e){throw new TemplateSourceException("PUBLISH_FAILED");}});}finally{s.close();} }
    private static void replace(Path root,Path staging)throws IOException{Path backup=root.resolveSibling("."+root.getFileName()+".backup");Files.move(root,backup);try{Files.move(staging,root);delete(backup);}catch(IOException e){Files.move(backup,root);throw e;}}
    private static void delete(Path r){try{java.util.stream.Stream<Path>s=Files.walk(r);try{s.sorted(Collections.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}finally{s.close();}}catch(IOException ignored){}}
}
