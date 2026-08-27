/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.List;

/** Central account-role policy shared by user-owned asset services. */
final class PlatformRolePolicy {

    private static final List<String> BUILD_ROLES =
            List.of("PLATFORM_ADMIN", "ORG_ADMIN", "BUILDER");

    private PlatformRolePolicy() {}

    static void requireBuilder(PlatformAuthService.Principal principal) {
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后管理资产");
        }
        if (!BUILD_ROLES.contains(principal.role())) {
            throw new PlatformAuthService.AuthException(403, "当前角色没有创建或修改资产的权限");
        }
    }

    static boolean canBuild(PlatformAuthService.Principal principal) {
        return principal != null && BUILD_ROLES.contains(principal.role());
    }
}
