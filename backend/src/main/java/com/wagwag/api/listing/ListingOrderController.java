package com.wagwag.api.listing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ListingOrderController {
    private final ListingOrderService orders;
    public ListingOrderController(ListingOrderService orders) { this.orders = orders; }

    @PostMapping("/api/listings/{id}/orders")
    public ListingOrderService.Order reserve(@PathVariable long id, @Valid @RequestBody OrderInput input) {
        return orders.reserve(id, input.clientOrderId());
    }
    @GetMapping("/api/listing-orders")
    public ListingOrderService.OrderPage list(@RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int page) { return orders.list(limit, page); }
    @GetMapping("/api/listing-orders/{id}")
    public ListingOrderService.Order detail(@PathVariable long id) { return orders.detail(id); }
    @PostMapping("/api/listing-orders/{id}/cancel")
    public ListingOrderService.Order cancel(@PathVariable long id) { return orders.cancel(id); }
    @PostMapping("/api/listing-orders/{id}/complete")
    public ListingOrderService.Order complete(@PathVariable long id) { return orders.complete(id); }
    @PutMapping("/api/listing-orders/{id}/rating")
    public ListingOrderService.Order rate(@PathVariable long id, @Valid @RequestBody RatingInput input) {
        return orders.rate(id, input.score(), input.comment());
    }
    public record OrderInput(@NotNull UUID clientOrderId) {}
    public record RatingInput(@Min(1) @Max(5) int score, @Size(max = 500) String comment) {}
}
