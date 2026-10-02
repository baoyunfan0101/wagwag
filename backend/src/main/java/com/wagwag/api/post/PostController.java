package com.wagwag.api.post;

import com.wagwag.api.storage.PostImageStorage.UploadTicket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PostController {
    private final PostService service;

    public PostController(PostService service) { this.service = service; }

    @PostMapping("/posts")
    public ResponseEntity<PostResponse> create(@Valid @RequestBody PostInput input) {
        PostResponse post = service.create(input);
        return ResponseEntity.created(URI.create("/api/posts/" + post.id())).body(post);
    }

    @PostMapping("/posts/media-uploads")
    public UploadTicket prepareImage(@Valid @RequestBody ImageUploadInput input) {
        return service.prepareImage(input.contentType());
    }

    @GetMapping("/posts/{id}")
    public PostResponse get(@PathVariable long id) { return service.get(id); }

    @GetMapping("/feed")
    public FeedPage feed(@RequestParam(defaultValue = "20") int limit,
                         @RequestParam(required = false) String cursor) {
        return service.feed(limit, cursor);
    }

    @PostMapping("/posts/{id}/likes")
    public PostResponse like(@PathVariable long id) { return service.like(id); }

    @DeleteMapping("/posts/{id}/likes")
    public PostResponse unlike(@PathVariable long id) { return service.unlike(id); }

    @PostMapping("/posts/{id}/comments")
    public ResponseEntity<CommentResponse> comment(@PathVariable long id,
                                                   @Valid @RequestBody CommentInput input) {
        CommentResponse comment = service.comment(id, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }

    @GetMapping("/posts/{id}/comments")
    public List<CommentResponse> comments(@PathVariable long id) { return service.comments(id); }

    public record ImageUploadInput(@NotBlank String contentType) {}
}
