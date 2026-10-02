package com.wagwag.api.post;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
    public FeedPage feed(int limit, String cursor) {
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feed limit must be between 1 and 50");
        }
        FeedCursor after = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, limit + 1);
        List<Post> rows = after == null ? posts.findFeed(page)
            : posts.findFeedAfter(after.createdAt(), after.id(), page);
        boolean hasMore = rows.size() > limit;
        List<Post> selected = hasMore ? rows.subList(0, limit) : rows;
        if (selected.isEmpty()) return new FeedPage(List.of(), null);

        List<Long> ids = selected.stream().map(Post::getId).toList();
        Map<Long, String> imageUrls = new HashMap<>();
        for (Object[] row : media.firstMediaCandidates(ids)) {
            imageUrls.putIfAbsent(((Number) row[0]).longValue(), (String) row[1]);
        }
        Map<Long, Long> likeCounts = counts(likes.countByPostIds(ids));
        Map<Long, Long> commentCounts = counts(comments.countByPostIds(ids));
        Set<Long> likedIds = devPetId > 0 ? new HashSet<>(likes.likedPostIds(ids, devPetId)) : Set.of();
        List<PostResponse> items = selected.stream().map(post -> {
            Pet pet = post.getPet();
            Long id = post.getId();
            return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
                post.getBody(), imageUrls.get(id), post.getCreatedAt(),
                likeCounts.getOrDefault(id, 0L), commentCounts.getOrDefault(id, 0L), likedIds.contains(id));
        }).toList();
        Post last = selected.getLast();
        return new FeedPage(items, hasMore ? encodeCursor(last) : null);
    }

    @Transactional
    public PostResponse like(long id) {
        Post post = find(id);
        Pet pet = actor();
        likes.insertIfAbsent(id, pet.getId());
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

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static String encodeCursor(Post post) {
        String value = post.getCreatedAt() + "|" + post.getId();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static FeedCursor decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Instant createdAt = Instant.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id < 1) throw new IllegalArgumentException();
            return new FeedCursor(createdAt, id);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid feed cursor", error);
        }
    }

    private record FeedCursor(Instant createdAt, long id) {}

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
