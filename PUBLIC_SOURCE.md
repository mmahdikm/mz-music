# Public source preparation

This snapshot starts with fresh Git history. It includes application source,
tests, synthetic fixture generators, dependency locks/checksums and vector artwork.

Excluded: original signing keys, passwords and certificate pin; APKs and archives;
SDKs and caches; device/app data; music; screenshots; raw validation/security logs;
machine-specific configuration; unrelated projects; prior Git history.

Build tools require explicitly configured JDK/Android SDK locations. Fresh builds
create their own private signing identity. Source archives use only reviewed Git
paths and normalized ZIP timestamps. Original application behavior is unchanged.

## Before publishing any update

1. Inspect every staged file and its diff. Do not force-add ignored outputs.
2. Run `python3 tools/check_public_source.py`. This checks both index blobs and
   working files for disallowed paths/types and common credential/personal-data
   patterns without printing matched values.
3. Review Git author and committer identity. The initial snapshot uses the project
   name and a non-deliverable placeholder address, not a personal name/email.
4. Check any intended release attachment separately; this review covers source.

The guard is defense in depth, not a comprehensive secret detector. Manual review
is still needed. The Gradle wrapper is the only allowed binary and should be
verified separately whenever changed. Public hosting necessarily displays the
repository owner account and anything that account makes public. This source
preparation does not make the hosting account anonymous.

No remote publication is performed by build or packaging tools. No open-source
license has been selected for the original project code or artwork.
