package com.wagwag.api.social;

import java.util.List;

public record PetListPage(List<PetSummary> items, Integer nextPage) {}
