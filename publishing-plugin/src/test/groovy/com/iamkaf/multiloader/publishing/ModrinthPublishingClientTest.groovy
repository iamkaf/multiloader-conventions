package com.iamkaf.multiloader.publishing

import spock.lang.Specification

class ModrinthPublishingClientTest extends Specification {

    def "API base keeps the v2 prefix when resolving routes"() {
        expect:
        ModrinthPublishingClient.API.resolve('project/IWGxWQWM').toString() == 'https://api.modrinth.com/v2/project/IWGxWQWM'
        ModrinthPublishingClient.API.resolve('version').toString() == 'https://api.modrinth.com/v2/version'
    }
}
