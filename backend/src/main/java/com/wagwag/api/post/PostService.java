package com.wagwag.api.post;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.storage.PostImageStorage;
import com.wagwag.api.storage.PostImageStorage.UploadTicket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
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
    private final PostImageStorage storage;
    private final long devPetId;

    public PostService(PostRepository posts, PostMediaRepository media, PostLikeRepository likes,
                       CommentRepository comments, PetRepository pets, PostImageStorage storage,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.posts = posts;
        this.media = media;
        this.likes = likes;
        this.comments = comments;
        this.pets = pets;
        this.storage = storage;
        this.devPetId = devPetId;
    }

    @Transactional
    public PostResponse create(PostInput input) {
        String body = trimNullable(input.body());
        String imageUrl = trimNullable(input.imageUrl());
        List<String> keys = input.imageKeys() == null ? List.of() : input.imageKeys();
        if (body == null && imageUrl == null && keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add text or an image");
        }
        if (imageUrl != null) validateImageUrl(imageUrl);
        if (imageUrl != null && !keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use image links or uploaded images");
        }
        if (keys.size() > 4 || keys.stream().anyMatch(key -> key == null || key.isBlank())
                || new HashSet<>(keys).size() != keys.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use up to four distinct images");
        }

        Pet pet = actor();
        List<String> urls = new ArrayList<>();
        if (imageUrl != null) urls.add(imageUrl);
        for (String key : keys) urls.add(storage.verifyAndGetUrl(pet.getId(), key));

        Post post = posts.saveAndFlush(new Post(pet, body));
        for (int index = 0; index < urls.size(); index++) {
            media.save(new PostMedia(post, urls.get(index), index));
        }
        media.flush();
        return response(post);
    }

    @Transactional(readOnly = true)
    public UploadTicket prepareImage(String contentType) {
        return storage.prepare(actor().getId(), contentType);
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
        Map<Long, List<String>> imageUrls = new HashMap<>();
        for (Object[] row : media.mediaForPosts(ids)) {
            imageUrls.computeIfAbsent(((Number) row[0]).longValue(), ignored -> new ArrayList<>())
                .add((String) row[1]);
        }
        Map<Long, Long> likeCounts = counts(likes.countByPostIds(ids));
        Map<Long, Long> commentCounts = counts(comments.countByPostIds(ids));
        Set<Long> likedIds = devPetId > 0 ? new HashSet<>(likes.likedPostIds(ids, devPetId)) : Set.of();
        List<PostResponse> items = selected.stream().map(post -> {
            Pet pet = post.getPet();
            Long id = post.getId();
            List<String> urls = imageUrls.getOrDefault(id, List.of());
            return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
                post.getBody(), firstUrl(urls), urls, post.getCreatedAt(),
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
        List<String> urls = media.findByPost_IdOrderBySortOrderAsc(id).stream()
            .map(PostMedia::getUrl).toList();
        return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
            post.getBody(), firstUrl(urls), urls, post.getCreatedAt(), likes.countByPost_Id(id),
            comments.countByPost_Id(id),
            devPetId > 0 && likes.existsByPost_IdAndPet_Id(id, devPetId));
    }

    private static String firstUrl(List<String> urls) {
        return urls.isEmpty() ? null : urls.getFirst();
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
