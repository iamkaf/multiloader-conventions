package com.iamkaf.multiloader.publishing

import spock.lang.Specification

class ReleaseChangelogSelectorTest extends Specification {

    def "extracts matching release entries and filters version-prefixed notes"() {
        given:
        def changelog = '''
# Changelog

## 1.2.3
- Shared note.
- 1.21.11: Matching line.
- 26.2: Wrong line.

## 1.2.2
- Older line.

## Types of changes
- Added for new features.
'''.stripIndent()

        when:
        def selected = ReleaseChangelogSelector.INSTANCE.extractSelectedChangelog(changelog, '1.2.3+1.21.11', ['1.21.11'])

        then:
        selected.contains('Shared note.')
        selected.contains('1.21.11: Matching line.')
        !selected.contains('26.2: Wrong line.')
        !selected.contains('Older line.')
    }
}
