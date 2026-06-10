package com.placeholder.domain.provider.controller;

import com.placeholder.domain.provider.dto.SettlementResponse;
import com.placeholder.domain.provider.service.ProviderAccountService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderAccountService providerAccountService;

    @PreAuthorize("hasRole('PROVIDER')")
    @GetMapping("/my/settlement")
    public ResponseEntity<SettlementResponse> getMySettlement(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        SettlementResponse response =
                providerAccountService.getMySettlement(userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
