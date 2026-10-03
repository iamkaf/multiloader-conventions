package com.iamkaf.multiloader.support.adapters

import spock.lang.Specification

class MixinRefmapInjectionTest extends Specification {

    def "adds the refmap key after the opening brace"() {
        expect:
        MixinRefmapInjection.INSTANCE.injectIntoLine(line, 'amber.refmap.json') == expected

        where:
        line                  | expected
        '{'                   | '{\n  "refmap": "amber.refmap.json",'
        '{"required": true,'  | '{\n  "refmap": "amber.refmap.json","required": true,'
        '  "required": true,' | null
    }

    def "keeps a refmap the mixin config already names"() {
        expect:
        MixinRefmapInjection.INSTANCE.declaresRefmap('{ "refmap" : "custom.refmap.json" }')
        !MixinRefmapInjection.INSTANCE.declaresRefmap('{ "package": "a.refmap" }')
    }
}
