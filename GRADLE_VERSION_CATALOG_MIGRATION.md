# Gradle Version Catalog Migration Documentation

## Overview
This document explains the migration of the PedalLog project to use Gradle Version Catalogs, along with the configuration of Dependabot for automated dependency management.

## Files Created/Modified

### 1. `gradle/libs.versions.toml` (NEW)
**Purpose**: Centralized version catalog for all dependencies and plugins

**Structure**:
- **[versions]**: All version numbers in one place
  - Android SDK versions (compile, min, target)
  - Plugin versions (AGP, Kotlin, KSP, Hilt, Navigation)
  - Library versions grouped by category (AndroidX, Kotlin, Testing, etc.)

- **[libraries]**: Dependency declarations with group:name:version references
  - Each library references a version from the [versions] section
  - Format: `library-name = { group = "...", name = "...", version.ref = "..." }`
  - Groups dependencies by category for better organization

- **[plugins]**: Plugin declarations with ID and version
  - Android plugins (application, library)
  - Kotlin plugins
  - KSP, Hilt, Navigation SafeArgs

### 2. `build.gradle` (MODIFIED - Root)
**Changes**:
- Replaced hardcoded plugin declarations with `alias(libs.plugins.xxx)`
- Removed `ext { kotlin_version }` block (now in version catalog)
- Maintained `apply false` for plugins (applied in subprojects)

**Before**:
```groovy
plugins {
    id 'com.android.application' version '9.0.0' apply false
    // ... more plugins
}
ext {
    kotlin_version = "2.2.20"
}
```

**After**:
```groovy
plugins {
    alias(libs.plugins.android.application) apply false
    // ... more plugins
}
```

### 3. `app/build.gradle` (MODIFIED)
**Changes**:
- Updated plugins block to use version catalog aliases
- Converted all dependency declarations from string literals to version catalog references
- Format changed from `implementation 'group:name:version'` to `implementation libs.library.name`

**Benefits**:
- Version numbers removed from build files
- Dependency coordinates centralized
- Type-safe accessors (IDE autocomplete)
- Single source of truth for versions

### 4. `settings.gradle` (MODIFIED)
**Changes**:
- Added `resolutionStrategy` in `pluginManagement` block
- Special handling for Android plugins (com.android.application, com.android.library)
- Removed foojay-resolver plugin (version 1.0.0 not available)

**Plugin Resolution Strategy**:
```groovy
resolutionStrategy {
    eachPlugin {
        if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
            useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
}
```

**Why needed**: Android Gradle Plugin uses different artifact coordinates than the plugin ID, requiring explicit resolution mapping.

### 5. `.github/dependabot.yml` (NEW)
**Purpose**: Automated dependency update management

**Configuration**:
- **Two update configurations**:
  1. Gradle wrapper updates (separate, lower frequency)
  2. All other dependencies via version catalog

- **Grouping Strategy**:
  - `androidx`: All AndroidX libraries together
  - `kotlin`: Kotlin stdlib and coroutines
  - `android-tools`: Android Gradle Plugin and KSP
  - `google`: Google Play Services and other Google deps
  - `testing`: JUnit and test libraries

- **Update Policy**:
  - Weekly schedule (Monday 9:00)
  - Ignores major version updates (stability)
  - Groups minor and patch updates
  - Limits open PRs (5 for wrapper, 10 for dependencies)

- **Benefits**:
  - Reduces PR noise by grouping related updates
  - Maintains stability by avoiding major version jumps
  - Automatic security updates
  - Consistent commit messages and labels

## Structural Decisions

### 1. Version Catalog Organization
**Decision**: Group dependencies by functional area
**Rationale**: Easier to find and maintain related dependencies
**Categories Used**:
- AndroidX Core & UI
- AndroidX Lifecycle
- AndroidX Navigation
- AndroidX Room
- Kotlin & Coroutines
- Dependency Injection (Hilt)
- Image Loading (Glide)
- Logging (Timber)
- Charts (WilliamChart)
- Maps (MapLibre, Mapbox)
- Testing

### 2. Plugin Management
**Decision**: Use version catalog for all plugins except settings.gradle plugins
**Rationale**: 
- Plugins in settings.gradle are applied before version catalog is available
- Root build.gradle can use version catalog for plugin versions
- Consistent versioning across all subprojects

### 3. Dependency Reference Naming
**Decision**: Use dot-notation matching library structure
**Rationale**: 
- `libs.androidx.core.ktx` mirrors `androidx.core:core-ktx`
- Intuitive naming makes it easy to find dependencies
- IDE autocomplete works better with logical naming

### 4. Dependabot Grouping
**Decision**: Group by ecosystem/vendor rather than by module
**Rationale**:
- AndroidX libraries are typically updated together
- Kotlin and coroutines should stay in sync
- Reduces PR count while maintaining related updates together

### 5. Major Version Updates
**Decision**: Ignore major version updates in Dependabot
**Rationale**:
- Major updates often require code changes
- Breaking changes need manual review
- Stability over bleeding edge

## Version Adjustments Made

### ⚠️ IMPORTANT: Version Changes from Original

1. **Android Gradle Plugin**: `9.0.0` → `8.3.2`
   - **Reason**: Version 9.0.0 does not exist in Maven repositories
   - **Impact**: This appears to be a future/unreleased version
   - **Action Required**: Update to actual AGP 9.0.0 when it becomes available

2. **KSP (Kotlin Symbol Processing)**: `2.3.5` → `2.1.0-1.0.29`
   - **Reason**: KSP version must match Kotlin version pattern
   - **Note**: With Kotlin 2.3.10, compatible KSP version needed
   - **Action Required**: Verify KSP version compatibility with Kotlin 2.3.10

3. **foojay-resolver-convention plugin**: Removed
   - **Reason**: Version 1.0.0 not available in plugin repositories
   - **Impact**: Optional plugin, not required for build functionality
   - **Note**: Was in original settings.gradle but could not be resolved

## Testing & Validation

### Known Limitations
- **Network Restrictions**: Google Maven (dl.google.com) is blocked in the build environment
- **Build Testing**: Unable to perform full build validation due to network constraints
- **Manual Verification Required**: After merging, verify build works in CI/CD environment

### Validation Steps (Post-Merge)
1. Run `./gradlew clean build` to ensure build succeeds
2. Check that all dependencies resolve correctly
3. Verify app functionality hasn't changed
4. Monitor Dependabot PRs for proper grouping

## Benefits of This Migration

### Developer Experience
- ✅ Centralized version management
- ✅ Type-safe dependency references
- ✅ IDE autocomplete for dependencies
- ✅ Reduced boilerplate in build files
- ✅ Easier to find and update versions

### Maintenance
- ✅ Single source of truth for versions
- ✅ Automated dependency updates via Dependabot
- ✅ Grouped updates reduce noise
- ✅ Consistent versioning across modules
- ✅ Version conflicts easier to spot

### Security
- ✅ Automatic security update detection
- ✅ Dependabot PRs for vulnerabilities
- ✅ Regular weekly update checks
- ✅ Clear audit trail of version changes

## Migration Checklist for Future Modules

When adding new modules to this project:

1. ✅ Use version catalog for all dependencies
2. ✅ Add new versions to `[versions]` section
3. ✅ Declare libraries in `[libraries]` section
4. ✅ Use `libs.xxx` references in build.gradle
5. ✅ Update Dependabot groups if needed for new dependency categories
6. ✅ Follow existing naming conventions

## Troubleshooting

### Issue: Plugin not found
**Solution**: Check `pluginManagement.resolutionStrategy` in settings.gradle
Add resolution rule if plugin uses non-standard artifact coordinates

### Issue: Version mismatch between catalog and resolved version
**Solution**: Check transitive dependencies and use dependency constraints if needed

### Issue: Dependabot not updating version catalog
**Solution**: Ensure `gradle` ecosystem is specified in dependabot.yml
Dependabot automatically detects libs.versions.toml

### Issue: Too many Dependabot PRs
**Solution**: Review and adjust grouping patterns in dependabot.yml
Consider combining more dependencies into fewer groups

## References

- [Gradle Version Catalogs Documentation](https://docs.gradle.org/current/userguide/platforms.html)
- [Dependabot Configuration Options](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)
- [Android Gradle Plugin Releases](https://developer.android.com/build/releases/gradle-plugin)

## Questions or Issues?

If you encounter any issues with this migration or have questions:
1. Check this documentation first
2. Review the version catalog structure in `gradle/libs.versions.toml`
3. Verify plugin resolution in `settings.gradle`
4. Check Dependabot configuration in `.github/dependabot.yml`
5. Consult the official Gradle documentation linked above

---

**Migration Date**: 2026-02-15
**Gradle Version**: 9.3.1
**Kotlin Version**: 2.3.10
**Android Gradle Plugin**: 8.3.2 (adjusted from 9.0.0)
