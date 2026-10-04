package com.wagwag.api.post;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.community.CommunityService;
import com.wagwag.api.community.CommunityService.CommunityLabel;
import com.wagwag.api.social.PetFollowRepository;
import com.wagwag.api.social.SocialRestrictions;
import com.wagwag.api.storage.PostImageStorage;
import com.wagwag.api.storage.PostVideoStorage;
import com.wagwag.api.storage.S3Objects.UploadTicket;
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
    private final PetFollowRepository follows;
    private final SocialRestrictions restrictions;
    private final CommunityService communities;
    private final PostImageStorage storage;
    private final PostVideoStorage videos;
    private final long devPetId;

    public PostService(PostRepository posts, PostMediaRepository media, PostLikeRepository likes,
                       CommentRepository comments, PetRepository pets, PostImageStorage storage, PostVideoStorage videos,
                       PetFollowRepository follows, SocialRestrictions restrictions,
                       CommunityService communities,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.posts = posts;
        this.media = media;
        this.likes = likes;
        this.comments = comments;
        this.pets = pets;
        this.storage = storage;
        this.videos = videos;
        this.follows = follows;
        this.restrictions = restrictions;
        this.communities = communities;
        this.devPetId = devPetId;
    }

    @Transactional
    public PostResponse create(PostInput input) {
        String body = trimNullable(input.body());
        List<String> keys = input.imageKeys() == null ? List.of() : input.imageKeys();
        String videoKey = input.videoKey();
        if (videoKey != null && (videoKey.isBlank() || !keys.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use photos or one video in a post");
        }
        if (body == null && keys.isEmpty() && videoKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add text, photos, or a video");
        }
        if (keys.size() > 4 || keys.stream().anyMatch(key -> key == null || key.isBlank())
                || new HashSet<>(keys).size() != keys.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use up to four distinct images");
        }

        Pet pet = actor();
        if (input.communityId() != null) communities.requireMember(input.communityId(), pet.getId());
        List<String> urls = new ArrayList<>();
        for (String key : keys) urls.add(storage.verifyAndGetUrl(pet.getId(), key));
        String videoUrl = videoKey == null ? null : videos.verifyAndGetUrl(pet.getId(), videoKey);

        Post post = posts.saveAndFlush(new Post(pet, body, videoUrl));
        if (input.communityId() != null) communities.attach(post.getId(), input.communityId());
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
    public UploadTicket prepareVideo(String contentType) {
        return videos.prepare(actor().getId(), contentType);
    }

    @Transactional(readOnly = true)
    public PostResponse get(long id) { return response(visiblePost(id)); }

    @Transactional(readOnly = true)
    public FeedPage feed(int limit, String cursor, boolean following) {
        return page(limit, cursor, following, null, false);
    }

    @Transactional(readOnly = true)
    public FeedPage communityFeed(long communityId, int limit, String cursor) {
        boolean moderationView = communities.detail(communityId).canModerate();
        return page(limit, cursor, false, communityId, moderationView);
    }

    private FeedPage page(int limit, String cursor, boolean following, Long communityId,
                          boolean moderationView) {
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feed limit must be between 1 and 50");
        }
        FeedCursor after = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, limit + 1);
        long actorId = actor().getId();
        List<Long> hidden = moderationView ? List.of(0L) : restrictions.hiddenFeedPetIds(actorId);
        if (hidden.isEmpty()) hidden = List.of(0L);
        List<Post> rows;
        if (communityId != null && moderationView) {
            rows = after == null ? posts.findCommunityModerationFeed(communityId, page)
                : posts.findCommunityModerationFeedAfter(communityId, after.createdAt(), after.id(), page);
        } else if (communityId != null) {
            rows = after == null ? posts.findCommunityFeed(communityId, actorId, hidden, page)
                : posts.findCommunityFeedAfter(communityId, actorId, hidden,
                    after.createdAt(), after.id(), page);
        } else if (following) {
            rows = after == null ? posts.findFollowingFeed(actorId, hidden, page)
                : posts.findFollowingFeedAfter(actorId, hidden, after.createdAt(), after.id(), page);
        } else {
            rows = after == null ? posts.findFeed(actorId, hidden, page)
                : posts.findFeedAfter(actorId, hidden, after.createdAt(), after.id(), page);
        }
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
        Map<Long, CommunityLabel> labels = communities.labels(ids);
        Set<Long> likedIds = devPetId > 0 ? new HashSet<>(likes.likedPostIds(ids, devPetId)) : Set.of();
        List<PostResponse> items = selected.stream().map(post -> {
            Pet pet = post.getPet();
            Long id = post.getId();
            List<String> urls = imageUrls.getOrDefault(id, List.of());
            CommunityLabel label = labels.get(id);
            return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
                label == null ? null : label.id(), label == null ? null : label.name(),
                post.getBody(), urls, post.getVideoUrl(), post.getCreatedAt(),
                likeCounts.getOrDefault(id, 0L), commentCounts.getOrDefault(id, 0L), likedIds.contains(id));
        }).toList();
        Post last = selected.getLast();
        return new FeedPage(items, hasMore ? encodeCursor(last) : null);
    }

    @Transactional
    public PostResponse like(long id) {
        Post post = visiblePost(id);
        Pet pet = actor();
        likes.insertIfAbsent(id, pet.getId());
        return response(post);
    }

    @Transactional
    public PostResponse unlike(long id) {
        Post post = visiblePost(id);
        Pet pet = actor();
        likes.deleteByPost_IdAndPet_Id(id, pet.getId());
        likes.flush();
        return response(post);
    }

    @Transactional
    public CommentResponse comment(long id, CommentInput input) {
        Comment comment = comments.saveAndFlush(new Comment(visiblePost(id), actor(), input.body().trim()));
        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(long id) {
        visiblePost(id);
        return comments.findByPost_IdOrderByCreatedAtAscIdAsc(id).stream()
            .map(CommentResponse::from).toList();
    }

    private PostResponse response(Post post) {
        Long id = post.getId();
        Pet pet = post.getPet();
        List<String> urls = media.findByPost_IdOrderBySortOrderAsc(id).stream()
            .map(PostMedia::getUrl).toList();
        CommunityLabel label = communities.labels(List.of(id)).get(id);
        return new PostResponse(id, pet.getId(), pet.getName(), pet.getAvatarUrl(),
            label == null ? null : label.id(), label == null ? null : label.name(),
            post.getBody(), urls, post.getVideoUrl(), post.getCreatedAt(), likes.countByPost_Id(id),
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

    private Post visiblePost(long id) {
        Post post = find(id);
        long actorId = actor().getId();
        long authorId = post.getPet().getId();
        if (actorId != authorId && (restrictions.blockedEitherWay(actorId, authorId)
                || (post.getPet().isPrivateProfile()
                    && !follows.existsByFollower_IdAndFollowing_IdAndAcceptedTrue(actorId, authorId)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }
        return post;
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
}
