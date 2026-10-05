package com.wagwag.api.listing;

import com.wagwag.api.listing.ListingService.ListingPage;
import com.wagwag.api.listing.ListingService.ListingResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import com.wagwag.api.storage.S3Objects;
import java.net.URI;
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
@RequestMapping("/api/listings")
public class ListingController {
    private final ListingService listings;

    public ListingController(ListingService listings) { this.listings = listings; }

    @PostMapping
    public ResponseEntity<ListingResponse> create(@Valid @RequestBody ListingInput input) {
        ListingResponse listing = listings.create(input);
        return ResponseEntity.created(URI.create("/api/listings/" + listing.id())).body(listing);
    }

    @GetMapping
    public ListingPage list(@RequestParam(defaultValue = "available") String scope,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor,
                            @RequestParam(defaultValue = "") String query) {
        return listings.list(scope, limit, cursor, query);
    }

    @PostMapping("/media-uploads")
    public S3Objects.UploadTicket upload(@Valid @RequestBody UploadInput input) { return listings.upload(input.contentType()); }

    @GetMapping("/recommended")
    public List<ListingResponse> recommended(@RequestParam(defaultValue = "10") int limit) { return listings.recommended(limit); }

    @GetMapping("/nearby")
    public ListingService.NearbyPage nearby(@RequestParam double latitude, @RequestParam double longitude,
        @RequestParam(defaultValue = "5000") int radiusMeters, @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int page) { return listings.nearby(latitude, longitude, radiusMeters, limit, page); }

    public record UploadInput(@NotBlank String contentType) {}

    @GetMapping("/{id}")
    public ListingResponse detail(@PathVariable long id) { return listings.detail(id); }

    @PostMapping("/{id}/favorites")
    public ListingResponse favorite(@PathVariable long id) { return listings.favorite(id); }

    @DeleteMapping("/{id}/favorites")
    public ListingResponse unfavorite(@PathVariable long id) { return listings.unfavorite(id); }

    @PostMapping("/{id}/sold")
    public ListingResponse sold(@PathVariable long id) { return listings.sold(id); }
}
