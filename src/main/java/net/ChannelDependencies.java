package net;

import client.processor.npc.MerchantStoreProcessor;
import service.NoteService;

import java.util.Objects;

public record ChannelDependencies(NoteService noteService, MerchantStoreProcessor merchantStoreProcessor) {

    public ChannelDependencies {
        Objects.requireNonNull(noteService);
        Objects.requireNonNull(merchantStoreProcessor);
    }
}
