package graphql.introspection


import graphql.TestUtil
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLNamedType
import spock.lang.Specification

import static graphql.GraphQL.newGraphQL

class IntrospectionTest extends Specification {

    def "bug 1186 - introspection depth check"() {
        def spec = '''
            type Query {
                geo : GeoPolygon 
            }
                
            type GeoPolygon {
                 coordinates: [[[[[Float]]]]]!
            }
        '''

        def graphQL = TestUtil.graphQL(spec).build()
        when:
        def executionResult = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY)
        then:
        executionResult.errors.isEmpty()

        def types = executionResult.data['__schema']['types'] as List
        def geoPolygonType = types.find { it['name'] == 'GeoPolygon' }
        def coordinatesField = (geoPolygonType['fields'] as List)[0]
        def fieldType = coordinatesField['type']
        // should show up to 7 levels deep like GraphIQL does
        fieldType == [
                kind  : 'NON_NULL',
                name  : null,
                ofType: [
                        kind  : 'LIST',
                        name  : null,
                        ofType: [
                                kind  : 'LIST',
                                name  : null,
                                ofType: [
                                        kind  : 'LIST',
                                        name  : null,
                                        ofType: [
                                                kind  : 'LIST',
                                                name  : null,
                                                ofType: [
                                                        kind  : 'LIST',
                                                        name  : null,
                                                        ofType: [
                                                                kind  : 'SCALAR',
                                                                name  : 'Float',
                                                                ofType: null]
                                                ]
                                        ]
                                ]
                        ]
                ]
        ]
    }

    def "schema description can be defined in SDL and queried via introspection"() {
        given:
        def sdl = ''' 
        """
        This is my schema
        """
        schema {
            query: Foo
        }
        
        type Foo {
            foo: String
        }
        
        '''
        def graphql = TestUtil.graphQL(sdl).build()
        when:
        def data = graphql.execute("{__schema { description }}").getData()

        then:
        data == [__schema: [description: "This is my schema"]]

    }

    def "introspection for repeatable directive info"() {
        def spec = '''
            directive @repeatableDirective(arg: String) repeatable on FIELD
             
            type Query {
               namedField: String
            }
        '''

        when:
        def graphQL = TestUtil.graphQL(spec).build()
        def executionResult = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY)

        then:
        executionResult.errors.isEmpty()

        def directives = executionResult.data.getAt("__schema").getAt("directives") as List
        def geoPolygonType = directives.find { it['name'] == 'repeatableDirective' }
        geoPolygonType["isRepeatable"] == true
    }

    def "can change data fetchers for introspection types"() {
        def sdl = '''
            type Query {
                inA : Int
                inB : Int
                inC : Int
                outA : Int
                outB : Int
                outC : Int
            }
        '''

        def schema = TestUtil.schema(sdl)
        def graphQL = newGraphQL(schema).build()
        def query = '''
            {
                __schema {
                    types {
                        name
                        fields {
                            name
                        }
                    }
                }
            }
        '''

        when:
        def er = graphQL.execute(query)
        then:
        def queryTypeFields = er.data["__schema"]["types"].find({ it["name"] == "Query" })["fields"]
        queryTypeFields == [[name: "inA"], [name: "inB"], [name: "inC"], [name: "outA"], [name: "outB"], [name: "outC"]]

        when:
        DataFetcher introspectionFieldsOfTypeFetcher = { env ->
            GraphQLNamedType type = env.getSource()
            if (type instanceof GraphQLFieldsContainer) {
                def fieldDefs = ((GraphQLFieldsContainer) type).getFieldDefinitions()
                return fieldDefs.stream().filter({ fld -> fld.getName().startsWith("in") }).collect()
            }
        }
        GraphQLCodeRegistry codeRegistry = schema.getCodeRegistry()
        codeRegistry = codeRegistry.transform({
            bld -> bld.dataFetcher(FieldCoordinates.coordinates("__Type", "fields"), introspectionFieldsOfTypeFetcher)
        }
        )
        schema = schema.transform({ bld -> bld.codeRegistry(codeRegistry) })
        graphQL = newGraphQL(schema).build()
        er = graphQL.execute(query)
        queryTypeFields = er.data["__schema"]["types"].find({ it["name"] == "Query" })["fields"]

        then:
        queryTypeFields == [[name: "inA"], [name: "inB"], [name: "inC"]]
    }
}
