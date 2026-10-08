package ar.emedicapp.documents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentMetadata> upload(
            @RequestParam MultipartFile file,
            @RequestParam String kind,
            @RequestParam(required = false) UUID branchId)
            throws IOException {
        var metadata = documents.upload(file, kind, branchId);
        return ResponseEntity.created(java.net.URI.create(metadata.downloadUrl())).body(metadata);
    }

    @GetMapping
    List<DocumentMetadata> list() {
        return documents.list();
    }

    @GetMapping("/{id}/metadata")
    DocumentMetadata metadata(@PathVariable UUID id) {
        return documents.metadata(id);
    }

    @GetMapping("/{id}")
    ResponseEntity<ByteArrayResource> download(@PathVariable UUID id) throws IOException {
        var download = documents.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.metadata().contentType()))
                .contentLength(download.bytes().length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        download.metadata().originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(new ByteArrayResource(download.bytes()));
    }
}
