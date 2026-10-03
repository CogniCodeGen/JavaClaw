// Included inside the desktop bridge's private namespace; metadata only.
std::string applicationCatalogIdentity(id value) {
    if (![value isKindOfClass:NSString.class]) return {};
    NSString *text = (NSString *)value;
    const char *utf8 = text.UTF8String;
    if (!utf8 || !validRequestedApplication(utf8)) return {};
    return utf8;
}

NSString *applicationCatalogBundleName(NSString *value) {
    return [value.pathExtension caseInsensitiveCompare:@"app"] == NSOrderedSame
        ? value.stringByDeletingPathExtension : value;
}

void addApplicationCatalogLocalizedAliases(NSBundle *bundle,
        jc_application_catalog::Application &application) {
    // The JVM can change Foundation's preferred language. Read only standard
    // bundle identity metadata for other declared localizations as well.
    @try {
        NSMutableArray<NSString *> *available = [NSMutableArray array];
        for (id locale in bundle.localizations) {
            if (applicationCatalogIdentity(locale).empty()) continue;
            [available addObject:(NSString *)locale];
            if (available.count >= 256) break;
        }
        [available sortUsingSelector:@selector(compare:)];
        NSMutableOrderedSet<NSString *> *ordered = [NSMutableOrderedSet orderedSet];
        for (id locale in bundle.preferredLocalizations) {
            if ([locale isKindOfClass:NSString.class] && [available containsObject:locale])
                [ordered addObject:(NSString *)locale];
        }
        [ordered addObjectsFromArray:available];
        NSString *bundlePrefix = [bundle.bundleURL.URLByResolvingSymlinksInPath.path
                stringByAppendingString:@"/"];
        size_t inspected = 0;
        for (NSString *locale in ordered) {
            if (++inspected > 32 || application.aliases.size() >= jc_application_catalog::kMaxAliases)
                break;
            @autoreleasepool {
                @try {
                    NSURL *metadata = [bundle URLForResource:@"InfoPlist" withExtension:@"strings"
                            subdirectory:nil localization:locale];
                    NSURL *resolved = metadata.URLByResolvingSymlinksInPath;
                    if (!resolved.isFileURL || ![resolved.path hasPrefix:bundlePrefix]) continue;
                    NSFileHandle *file = [NSFileHandle fileHandleForReadingFromURL:resolved
                            error:nullptr];
                    if (!file) continue;
                    NSData *data = nil;
                    @try {
                        data = [file readDataUpToLength:65537 error:nullptr];
                    } @finally {
                        [file closeAndReturnError:nullptr];
                    }
                    if (!data.length || data.length > 65536) continue;
                    id decoded = [NSPropertyListSerialization propertyListWithData:data
                            options:NSPropertyListImmutable format:nullptr error:nullptr];
                    if (![decoded isKindOfClass:NSDictionary.class]) continue;
                    NSDictionary *identity = (NSDictionary *)decoded;
                    jc_application_catalog::addAlias(application,
                            applicationCatalogIdentity(identity[@"CFBundleDisplayName"]));
                    jc_application_catalog::addAlias(application,
                            applicationCatalogIdentity(identity[@"CFBundleName"]));
                } @catch (NSException *invalidMetadata) {
                    (void)invalidMetadata;
                    // Missing or malformed localized metadata does not hide the app.
                }
            }
        }
    } @catch (NSException *invalidLocalizations) {
        (void)invalidLocalizations;
    }
}

void addApplicationCatalogBundle(NSURL *url,
        std::vector<jc_application_catalog::Application> &applications,
        std::unordered_map<std::string, size_t> &indices) {
    NSBundle *bundle = [NSBundle bundleWithURL:url];
    std::string identifier = applicationCatalogIdentity(bundle.bundleIdentifier);
    if (identifier.empty()) return;
    std::string key = applicationCatalogIdentity(bundle.bundleIdentifier.lowercaseString);
    if (key.empty()) return;
    NSDictionary *info = bundle.infoDictionary;
    NSDictionary *localized = bundle.localizedInfoDictionary;
    NSString *localizedFileName = nil;
    [url getResourceValue:&localizedFileName forKey:NSURLLocalizedNameKey error:nullptr];
    NSArray *candidates = @[
        url.URLByDeletingPathExtension.lastPathComponent ?: @"",
        localized[@"CFBundleDisplayName"] ?: @"",
        localized[@"CFBundleName"] ?: @"",
        info[@"CFBundleDisplayName"] ?: @"",
        info[@"CFBundleName"] ?: @"",
        info[@"CFBundleExecutable"] ?: @"",
        applicationCatalogBundleName(localizedFileName ?: @"")
    ];
    std::vector<std::string> names;
    for (id candidate in candidates) {
        std::string name = applicationCatalogIdentity(candidate);
        if (!name.empty()) names.push_back(std::move(name));
    }
    if (names.empty()) names.push_back(identifier);
    auto found = indices.find(key);
    if (found == indices.end()) {
        jc_application_catalog::Application application;
        application.name = names.front();
        application.displayName = names.front();
        for (id candidate in @[
                localized[@"CFBundleDisplayName"] ?: @"",
                localized[@"CFBundleName"] ?: @"",
                applicationCatalogBundleName(localizedFileName ?: @""),
                info[@"CFBundleDisplayName"] ?: @"",
                info[@"CFBundleName"] ?: @""]) {
            std::string display = applicationCatalogIdentity(candidate);
            if (!display.empty()) {
                application.displayName = std::move(display);
                break;
            }
        }
        application.applicationId = identifier;
        application.launchName = identifier;
        applications.push_back(std::move(application));
        found = indices.emplace(std::move(key), applications.size() - 1).first;
    }
    auto &application = applications[found->second];
    jc_application_catalog::addAlias(application, identifier);
    jc_application_catalog::addAlias(application, application.name);
    jc_application_catalog::addAlias(application, application.displayName);
    addApplicationCatalogLocalizedAliases(bundle, application);
    for (const auto &name : names) jc_application_catalog::addAlias(application, name);
}

std::string installedApplicationCatalog() {
    std::vector<jc_application_catalog::Application> applications;
    std::unordered_map<std::string, size_t> indices;
    NSFileManager *manager = NSFileManager.defaultManager;
    // Search only OS application roots, never document folders or bundle contents.
    NSArray<NSString *> *locations = @[
        @"/Applications", @"/System/Applications",
        [NSHomeDirectory() stringByAppendingPathComponent:@"Applications"]
    ];
    NSArray<NSURLResourceKey> *keys = @[
        NSURLIsDirectoryKey, NSURLIsSymbolicLinkKey, NSURLIsPackageKey,
        NSURLLocalizedNameKey
    ];
    __block bool truncated = false;
    size_t inspected = 0;
    for (NSString *location in locations) {
        NSURL *root = [NSURL fileURLWithPath:location isDirectory:YES];
        NSNumber *isSymbolicLink = nil;
        [root getResourceValue:&isSymbolicLink forKey:NSURLIsSymbolicLinkKey error:nullptr];
        if (isSymbolicLink.boolValue) {
            truncated = true;
            continue;
        }
        BOOL directory = NO;
        if (![manager fileExistsAtPath:location isDirectory:&directory] || !directory) continue;
        NSDirectoryEnumerator<NSURL *> *entries = [manager enumeratorAtURL:root
                includingPropertiesForKeys:keys
                options:NSDirectoryEnumerationSkipsPackageDescendants
                    | NSDirectoryEnumerationSkipsHiddenFiles
                errorHandler:^BOOL(NSURL *, NSError *) {
                    truncated = true;
                    return YES;
                }];
        if (!entries) {
            truncated = true;
            continue;
        }
        for (NSURL *candidate in entries) {
            if (++inspected > 4096) {
                truncated = true;
                break;
            }
            NSNumber *directoryValue = nil;
            NSNumber *symbolicLink = nil;
            [candidate getResourceValue:&directoryValue forKey:NSURLIsDirectoryKey error:nullptr];
            [candidate getResourceValue:&symbolicLink forKey:NSURLIsSymbolicLinkKey error:nullptr];
            if (symbolicLink.boolValue) {
                [entries skipDescendants];
                continue;
            }
            if (!directoryValue.boolValue) continue;
            if ([candidate.pathExtension caseInsensitiveCompare:@"app"] == NSOrderedSame) {
                [entries skipDescendants];
                addApplicationCatalogBundle(candidate, applications, indices);
            } else if (entries.level >= 4) {
                [entries skipDescendants];
                truncated = true;
            }
        }
        if (inspected > 4096) break;
    }
    return jc_application_catalog::boundedJson(std::move(applications), truncated);
}
