/*
 *    Copyright (c) 2022, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.useridmapping;

import io.supertokens.Main;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeStorage;
import io.supertokens.pluginInterface.emailverification.EmailVerificationStorage;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.jwt.JWTRecipeStorage;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.pluginInterface.session.SessionStorage;
import io.supertokens.pluginInterface.useridmapping.UserIdMappingStorage;
import io.supertokens.pluginInterface.useridmapping.exception.UnknownSuperTokensUserIdException;
import io.supertokens.pluginInterface.useridmapping.exception.UserIdMappingAlreadyExistsException;
import io.supertokens.pluginInterface.usermetadata.UserMetadataStorage;
import io.supertokens.pluginInterface.userroles.UserRolesStorage;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.webserver.WebserverAPI;
import jakarta.servlet.ServletException;
import org.jetbrains.annotations.TestOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;

public class UserIdMapping {

    public static void createUserIdMapping(TenantIdentifier tenantIdentifier, Main main,
                                           String superTokensUserId, String externalUserId,
                                           String externalUserIdInfo, boolean force)
            throws UnknownSuperTokensUserIdException,
            UserIdMappingAlreadyExistsException, StorageQueryException, ServletException, TenantOrAppNotFoundException {
        // if a userIdMapping is created with force, then we skip the following checks
        if (!force) {
            // check that none of the non-auth recipes are using the superTokensUserId
            assertThatUserIdIsNotBeingUsedInNonAuthRecipes(tenantIdentifier, main, superTokensUserId);

            // We do not allow for a UserIdMapping to be created when the externalUserId is a SuperTokens userId.
            // There could be a case where User_1 has a userId mapping and a new SuperTokens User, User_2 is created
            // whose userId is equal to the User_1's externalUserId.
            // Theoretically this could happen but the likelihood of generating a non-unique UUID is low enough that we
            // ignore it.

            {
                if (StorageLayer.getAuthRecipeStorage(tenantIdentifier, main)
                        .doesUserIdExist(tenantIdentifier.toAppIdentifier(), externalUserId)) {
                    throw new ServletException(new WebserverAPI.BadRequestException(
                            "Cannot create a userId mapping where the externalId is also a SuperTokens userID"));
                }
            }
        }

        StorageLayer.getUserIdMappingStorage(tenantIdentifier, main)
                .createUserIdMapping(tenantIdentifier.toAppIdentifier(), superTokensUserId, externalUserId,
                        externalUserIdInfo);
    }

    @TestOnly
    public static void createUserIdMapping(Main main,
                                           String superTokensUserId, String externalUserId,
                                           String externalUserIdInfo, boolean force)
            throws UnknownSuperTokensUserIdException,
            UserIdMappingAlreadyExistsException, StorageQueryException, ServletException {
        try {
            createUserIdMapping(new TenantIdentifier(null, null, null), main, superTokensUserId, externalUserId,
                    externalUserIdInfo, force);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static io.supertokens.pluginInterface.useridmapping.UserIdMapping getUserIdMapping(
            TenantIdentifier tenantIdentifier, Main main, String userId,
            UserIdType userIdType)
            throws StorageQueryException, TenantOrAppNotFoundException {
        UserIdMappingStorage storage = StorageLayer.getUserIdMappingStorage(tenantIdentifier, main);

        if (userIdType == UserIdType.SUPERTOKENS) {
            return storage.getUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, true);
        }
        if (userIdType == UserIdType.EXTERNAL) {
            return storage.getUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, false);
        }

        io.supertokens.pluginInterface.useridmapping.UserIdMapping[] userIdMappings = storage.getUserIdMapping(
                tenantIdentifier.toAppIdentifier(), userId);

        if (userIdMappings.length == 0) {
            return null;
        }

        if (userIdMappings.length == 1) {
            return userIdMappings[0];
        }

        if (userIdMappings.length == 2) {
            for (io.supertokens.pluginInterface.useridmapping.UserIdMapping userIdMapping : userIdMappings) {
                if (userIdMapping.superTokensUserId.equals(userId)) {
                    return userIdMapping;
                }
            }
        }

        throw new IllegalStateException("Retrieved more than 2 UserId Mapping entries for a single userId.");
    }

    @TestOnly
    public static io.supertokens.pluginInterface.useridmapping.UserIdMapping getUserIdMapping(
            Main main, String userId,
            UserIdType userIdType)
            throws StorageQueryException {
        try {
            return getUserIdMapping(new TenantIdentifier(null, null, null), main, userId, userIdType);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean deleteUserIdMapping(TenantIdentifier tenantIdentifier, Main main, String userId,
                                              UserIdType userIdType, boolean force)
            throws StorageQueryException, ServletException, TenantOrAppNotFoundException {

        UserIdMappingStorage storage = StorageLayer.getUserIdMappingStorage(tenantIdentifier, main);

        // referring to
        // https://docs.google.com/spreadsheets/d/17hYV32B0aDCeLnSxbZhfRN2Y9b0LC2xUF44vV88RNAA/edit?usp=sharing
        // we need to check if db is in A3 or A4.
        io.supertokens.pluginInterface.useridmapping.UserIdMapping mapping = getUserIdMapping(tenantIdentifier, main,
                userId,
                UserIdType.ANY);
        if (mapping != null) {
            if (StorageLayer.getAuthRecipeStorage(tenantIdentifier, main)
                    .doesUserIdExist(tenantIdentifier.toAppIdentifier(), mapping.externalUserId)) {
                // this means that the db is in state A4
                return storage.deleteUserIdMapping(tenantIdentifier.toAppIdentifier(), mapping.superTokensUserId, true);
            }
        } else {
            return false;
        }

        // if a userIdMapping is deleted with force, then we skip the following checks
        if (!force) {
            String externalId = mapping.externalUserId;

            // check if externalId is used in any non-auth recipes
            assertThatUserIdIsNotBeingUsedInNonAuthRecipes(tenantIdentifier, main, externalId);
        }

        // db is in state A3
        if (userIdType == UserIdType.SUPERTOKENS) {
            return storage.deleteUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, true);
        }
        if (userIdType == UserIdType.EXTERNAL) {
            return storage.deleteUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, false);
        }

        AuthRecipeStorage authRecipeStorage = StorageLayer.getAuthRecipeStorage(tenantIdentifier, main);
        if (authRecipeStorage.doesUserIdExist(tenantIdentifier.toAppIdentifier(), userId)) {
            return storage.deleteUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, true);
        }

        return storage.deleteUserIdMapping(tenantIdentifier.toAppIdentifier(), userId, false);
    }

    @TestOnly
    public static boolean deleteUserIdMapping(Main main, String userId,
                                              UserIdType userIdType, boolean force)
            throws StorageQueryException, ServletException {
        try {
            return deleteUserIdMapping(new TenantIdentifier(null, null, null), main, userId, userIdType, force);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean updateOrDeleteExternalUserIdInfo(TenantIdentifier tenantIdentifier, Main main,
                                                           String userId, UserIdType userIdType,
                                                           @Nullable String externalUserIdInfo)
            throws StorageQueryException, TenantOrAppNotFoundException {
        UserIdMappingStorage storage = StorageLayer.getUserIdMappingStorage(tenantIdentifier, main);

        if (userIdType == UserIdType.SUPERTOKENS) {
            return storage.updateOrDeleteExternalUserIdInfo(tenantIdentifier.toAppIdentifier(), userId, true,
                    externalUserIdInfo);
        }
        if (userIdType == UserIdType.EXTERNAL) {
            return storage.updateOrDeleteExternalUserIdInfo(tenantIdentifier.toAppIdentifier(), userId, false,
                    externalUserIdInfo);
        }

        AuthRecipeStorage authRecipeStorage = StorageLayer.getAuthRecipeStorage(tenantIdentifier, main);
        if (authRecipeStorage.doesUserIdExist(tenantIdentifier.toAppIdentifier(), userId)) {
            return storage.updateOrDeleteExternalUserIdInfo(tenantIdentifier.toAppIdentifier(), userId, true,
                    externalUserIdInfo);
        }

        return storage.updateOrDeleteExternalUserIdInfo(tenantIdentifier.toAppIdentifier(), userId, false,
                externalUserIdInfo);
    }

    @TestOnly
    public static boolean updateOrDeleteExternalUserIdInfo(Main main,
                                                           String userId, UserIdType userIdType,
                                                           @Nullable String externalUserIdInfo)
            throws StorageQueryException {
        try {
            return updateOrDeleteExternalUserIdInfo(new TenantIdentifier(null, null, null), main, userId, userIdType,
                    externalUserIdInfo);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HashMap<String, String> getUserIdMappingForSuperTokensUserIds(TenantIdentifier tenantIdentifier,
                                                                                Main main,
                                                                                ArrayList<String> userIds)
            throws StorageQueryException, TenantOrAppNotFoundException {
        return StorageLayer.getUserIdMappingStorage(tenantIdentifier, main)
                .getUserIdMappingForSuperTokensIds(tenantIdentifier.toAppIdentifier(), userIds);
    }

    @TestOnly
    public static HashMap<String, String> getUserIdMappingForSuperTokensUserIds(Main main,
                                                                                ArrayList<String> userIds)
            throws StorageQueryException {
        try {
            return getUserIdMappingForSuperTokensUserIds(new TenantIdentifier(null, null, null), main, userIds);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertThatUserIdIsNotBeingUsedInNonAuthRecipes(TenantIdentifier tenantIdentifier,
                                                                       Main main, String userId)
            throws StorageQueryException, ServletException, TenantOrAppNotFoundException {
        {
            if (StorageLayer.getStorage(tenantIdentifier, main)
                    .isUserIdBeingUsedInNonAuthRecipe(tenantIdentifier.toAppIdentifier(),
                            SessionStorage.class.getName(),
                            userId)) {
                throw new ServletException(
                        new WebserverAPI.BadRequestException("UserId is already in use in Session recipe"));
            }
        }
        {
            if (StorageLayer.getStorage(tenantIdentifier, main)
                    .isUserIdBeingUsedInNonAuthRecipe(tenantIdentifier.toAppIdentifier(),
                            UserMetadataStorage.class.getName(),
                            userId)) {
                throw new ServletException(
                        new WebserverAPI.BadRequestException("UserId is already in use in UserMetadata recipe"));
            }
        }
        {
            if (StorageLayer.getStorage(tenantIdentifier, main)
                    .isUserIdBeingUsedInNonAuthRecipe(tenantIdentifier.toAppIdentifier(),
                            UserRolesStorage.class.getName(),
                            userId)) {
                throw new ServletException(
                        new WebserverAPI.BadRequestException("UserId is already in use in UserRoles recipe"));
            }
        }
        {
            if (StorageLayer.getStorage(tenantIdentifier, main)
                    .isUserIdBeingUsedInNonAuthRecipe(tenantIdentifier.toAppIdentifier(),
                            EmailVerificationStorage.class.getName(),
                            userId)) {
                throw new ServletException(
                        new WebserverAPI.BadRequestException("UserId is already in use in EmailVerification recipe"));
            }
        }
        {
            if (StorageLayer.getStorage(tenantIdentifier, main)
                    .isUserIdBeingUsedInNonAuthRecipe(tenantIdentifier.toAppIdentifier(),
                            JWTRecipeStorage.class.getName(),
                            userId)) {
                throw new ServletException(new WebserverAPI.BadRequestException("Should never come here"));
            }
        }
    }
}
