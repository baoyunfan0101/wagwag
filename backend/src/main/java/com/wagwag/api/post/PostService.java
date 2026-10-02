package com.wagwag.api.post;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostService {
    private final PostRepository posts;
    private final PostMediaRepository media;
    private final PostLikeRepository likes;
    private final CommentRepository comments;
    private final PetRepository pets;
    private final long devPetId;

    public PostService(PostRepository posts, PostMediaRepository media, PostLikeRepository likes,
                       CommentRepository comments, PetRepository pets,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.posts = posts;
        this.media = media;
        this.likes = likes;
        this.comments = comments;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public PostResponse create(PostInput input) {
        String body = trimNullable(input.body());
        String imageUrl = trimNullable(input.imageUrl());
        if (body == null && imageUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add text or an image link");
        }
        if (imageUrl != null) validateImageUrl(imageUrl);

        Post post = posts.saveAndFlush(new Post(actor(), body));
        if (imageUrl != null) media.saveAndFlush(new PostMedia(post, imageUrl));
        return response(post);
    }

    @Transactional(readOnly = true)
    public PostResponse get(long id) { return response(find(id)); }

    @Transactional(readOnly = true)
    public List<PostResponse> feed() {
        return posts.findAllByOrderByCreatedAtDescIdDesc().stream().map(this::response).toList();
    }

    @Transactional
    public PostResponse like(long id) {
        Post post = find(id);
        Pet pet = actor();
        if (!likes.existsByPost_IdAndPet_Id(id, pet.getId())) {
            likes.saveAndFlush(new PostLike(post, pet));
        }
        return response(post);
    }

    @Transactional
    public PostResponse unlike(long id) {
        Post post = find(id);
        Pet pet = actor();
        likes.deleteByPost_IdAndPet_Id(id, pet.getId());
        likes.flush();
        return response(post);
    }

    @Transactional
    public CommentResponse comment(long id, CommentInput input) {
        Comment comment = comments.saveAndFlush(new Comment(find(id), actor(), input.body().trim()));
        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(long id) {
        find(id);
        return comments.findByPost_IdOrderByCreatedAtAscIdAsc(id).stream()
            .map(CommentResponse::from).toList();
    }

    private PostResponse response(Post post) {
        Long id = post.getId();
        Pet pet = post.getPet();
        String imageUrl = media.findFirstByPost_IdOrderBySortOrderAsc(id)
            .map(PostMedia::getUrl).orElse(null);
        return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
            post.getBody(), imageUrl, post.getCreatedAt(), likes.countByPost_Id(id),
            comments.countByPost_Id(id),
            devPetId > 0 && likes.existsByPost_IdAndPet_Id(id, devPetId));
    }

    private Post find(long id) {
        return posts.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
    }

    private Pet actor() {
        if (devPetId < 1) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is not configured");
        }
        return pets.findById(devPetId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing"));
    }

    private static String trimNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateImageUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid HTTP image link", error);
        }
    }
}
