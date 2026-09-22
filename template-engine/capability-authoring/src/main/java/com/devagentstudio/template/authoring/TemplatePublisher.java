package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.source.CapabilityV2Loader;
import com.devagentstudio.template.engine.source.TemplateSourceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/** Publishes a verified draft by atomically committing a strictly newer revision. */
public final class TemplatePublisher {
    public void publish(Path rawRoot, String revision, Path expectedProject, String capabilityId, PublicationGate gate) {
        if (gate == null) throw new TemplateSourceException("PUBLICATION_GATES_REQUIRED");
        Path root = rawRoot.toAbsolutePath().normalize(), staging;
        try { staging = Files.createTempDirectory(root.getParent(), ".template-publish-"); copy(root, staging); }
        catch (IOException e) { throw new TemplateSourceException("PUBLISH_FAILED: " + e.getMessage()); }
        try {
            String previous = new String(Files.readAllBytes(staging.resolve("template-revision.txt")), StandardCharsets.UTF_8).trim();
            if (!isStrictlyNewer(revision, previous)) throw new TemplateSourceException("TEMPLATE_REVISION_INVALID");
            new DraftTemplateSourceValidator().validate(staging);
            new RoundTripVerifier().verify(staging, expectedProject, capabilityId);
            gate.verifyExecutorEquivalence(staging); gate.verifyValidators(staging);
            Files.write(staging.resolve("template-revision.txt"), (revision + "\n").getBytes(StandardCharsets.UTF_8));
            new CapabilityV2Loader().load(staging);
            replace(root, staging);
        } catch (IOException e) { delete(staging); throw new TemplateSourceException("PUBLISH_FAILED: " + e.getMessage()); }
        catch (RuntimeException e) { delete(staging); throw e; }
    }
    private static boolean isStrictlyNewer(String candidate, String current) {
        int[] next = revision(candidate), previous = revision(current);
        if (next == null || previous == null) return false;
        for (int index = 0; index < next.length; index++) {
            if (next[index] != previous[index]) return next[index] > previous[index];
        }
        return false;
    }
    private static int[] revision(String value) {
        if (value == null || !value.matches("\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d+")) return null;
        String[] parts = value.split("\\.");
        try { return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) }; }
        catch (NumberFormatException e) { return null; }
    }
    private static void copy(Path from, Path to) throws IOException { java.util.stream.Stream<Path> s=Files.walk(from); try{s.forEach(p->{try{Path t=to.resolve(from.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(t);else Files.copy(p,t);}catch(IOException e){throw new TemplateSourceException("PUBLISH_FAILED");}});}finally{s.close();} }
    private static void replace(Path root,Path staging)throws IOException{Path backup=root.resolveSibling("."+root.getFileName()+".backup");Files.move(root,backup);try{Files.move(staging,root);delete(backup);}catch(IOException e){Files.move(backup,root);throw e;}}
    private static void delete(Path r){try{java.util.stream.Stream<Path>s=Files.walk(r);try{s.sorted(Collections.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}finally{s.close();}}catch(IOException ignored){}}
}
