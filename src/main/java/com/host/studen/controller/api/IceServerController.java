package com.host.studen.controller.api;

import com.host.studen.service.IceServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves WebRTC ICE server config (STUN + optional free TURN relay) to any
 * authenticated participant (host or student) joining a meeting room.
 * See {@link IceServerService} for the TURN fallback strategy.
 */
@RestController
@RequestMapping("/api")
public class IceServerController {

    @Autowired
    private IceServerService iceServerService;

    @GetMapping("/ice-servers")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> getIceServers() {
        return iceServerService.getIceServers();
    }
}
