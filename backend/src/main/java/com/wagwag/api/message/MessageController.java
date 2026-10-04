package com.wagwag.api.message;

import com.wagwag.api.message.MessageService.Conversation;
import com.wagwag.api.message.MessageService.ConversationPage;
import com.wagwag.api.message.MessageService.Message;
import com.wagwag.api.message.MessageService.MessagePage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class MessageController {
    private final MessageService messages;

    public MessageController(MessageService messages) { this.messages = messages; }

    @PostMapping
    public Conversation open(@Valid @RequestBody ConversationInput input) { return messages.open(input.petId()); }

    @GetMapping
    public ConversationPage list(@RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(defaultValue = "0") int page) {
        return messages.list(limit, page);
    }

    @GetMapping("/{id}")
    public Conversation detail(@PathVariable long id) { return messages.detail(id); }

    @GetMapping("/{id}/messages")
    public MessagePage history(@PathVariable long id, @RequestParam(defaultValue = "30") int limit,
                               @RequestParam(required = false) Long beforeId,
                               @RequestParam(required = false) Long afterId) {
        return messages.history(id, limit, beforeId, afterId);
    }

    @PostMapping("/{id}/messages")
    public Message send(@PathVariable long id, @Valid @RequestBody MessageInput input) {
        return messages.send(id, input);
    }

    @PutMapping("/{id}/receipt")
    public Conversation receipt(@PathVariable long id, @Valid @RequestBody ReceiptInput input) {
        return messages.receipt(id, input);
    }
}
