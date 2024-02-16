/*
 * Copyright (c) 2024, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.data.jsondata.json;

import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.flags.SymbolFlags;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.MapType;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.TypeUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.data.jsondata.utils.DiagnosticErrorCode;
import io.ballerina.stdlib.data.jsondata.utils.DiagnosticLog;
import org.apache.commons.lang3.StringEscapeUtils;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;

/**
 * This class converts string to Json with projection.
 *
 * @since 0.1.0
 */
public class JsonParser {

    private static final ThreadLocal<StateMachine> tlStateMachine = ThreadLocal.withInitial(StateMachine::new);

    /**
     * Parses the contents in the given {@link Reader} and returns a json.
     *
     * @param reader reader which contains the JSON content
     * @return JSON structure
     * @throws BError for any parsing error
     */
    public static Object parse(Reader reader, Type type)
            throws BError {
        StateMachine sm = tlStateMachine.get();
        try {
            return sm.execute(reader, TypeUtils.getReferredType(type));
        } finally {
            // Need to reset the state machine before leaving. Otherwise, references to the created
            // JSON values will be maintained and the java GC will not happen properly.
            sm.reset();
        }
    }

    /**
     * Represents a JSON parser related exception.
     */
    public static class JsonParserException extends Exception {
        public JsonParserException(String msg) {
            super(msg);
        }
    }

    /**
     * Represents the state machine used for JSON parsing.
     */
    static class StateMachine {

        private static final char CR = 0x000D;
        private static final char NEWLINE = 0x000A;
        private static final char HZ_TAB = 0x0009;
        private static final char SPACE = 0x0020;
        private static final char BACKSPACE = 0x0008;
        private static final char FORMFEED = 0x000C;
        private static final char QUOTES = '"';
        private static final char REV_SOL = '\\';
        private static final char SOL = '/';
        private static final char EOF = (char) -1;
        private static final State DOC_START_STATE = new DocumentStartState();
        private static final State DOC_END_STATE = new DocumentEndState();
        static final State FIRST_FIELD_READY_STATE = new FirstFieldReadyState();
        private static final State NON_FIRST_FIELD_READY_STATE = new NonFirstFieldReadyState();
        private static final State FIELD_NAME_STATE = new FieldNameState();
        private static final State END_FIELD_NAME_STATE = new EndFieldNameState();
        private static final State FIELD_VALUE_READY_STATE = new FieldValueReadyState();
        private static final State STRING_FIELD_VALUE_STATE = new StringFieldValueState();
        private static final State NON_STRING_FIELD_VALUE_STATE = new NonStringFieldValueState();
        private static final State NON_STRING_VALUE_STATE = new NonStringValueState();
        private static final State STRING_VALUE_STATE = new StringValueState();
        private static final State FIELD_END_STATE = new FieldEndState();
        private static final State STRING_AE_ESC_CHAR_PROCESSING_STATE = new StringAEEscapedCharacterProcessingState();
        private static final State STRING_AE_PROCESSING_STATE = new StringAEProcessingState();
        private static final State FIELD_NAME_UNICODE_HEX_PROCESSING_STATE = new FieldNameUnicodeHexProcessingState();
        static final State FIRST_ARRAY_ELEMENT_READY_STATE = new FirstArrayElementReadyState();
        private static final State NON_FIRST_ARRAY_ELEMENT_READY_STATE = new NonFirstArrayElementReadyState();
        private static final State STRING_ARRAY_ELEMENT_STATE = new StringArrayElementState();
        private static final State NON_STRING_ARRAY_ELEMENT_STATE = new NonStringArrayElementState();
        private static final State ARRAY_ELEMENT_END_STATE = new ArrayElementEndState();
        private static final State STRING_FIELD_ESC_CHAR_PROCESSING_STATE =
                new StringFieldEscapedCharacterProcessingState();
        private static final State STRING_VAL_ESC_CHAR_PROCESSING_STATE =
                new StringValueEscapedCharacterProcessingState();
        private static final State FIELD_NAME_ESC_CHAR_PROCESSING_STATE =
                new FieldNameEscapedCharacterProcessingState();
        private static final State STRING_FIELD_UNICODE_HEX_PROCESSING_STATE =
                new StringFieldUnicodeHexProcessingState();
        private static final State STRING_VALUE_UNICODE_HEX_PROCESSING_STATE =
                new StringValueUnicodeHexProcessingState();

        Object currentJsonNode;
        Deque<Object> nodesStack;
        // TODO: Need group same level field and keep the hierarchy.
        Deque<String> fieldNames;

        private StringBuilder hexBuilder = new StringBuilder(4);
        private char[] charBuff = new char[1024];
        private int charBuffIndex;

        private int index;
        private int line;
        private int column;
        private char currentQuoteChar;
        Field currentField;
        Stack<Map<String, Field>> fieldHierarchy = new Stack<>();
        Stack<Type> restType = new Stack<>();
        Stack<Type> expectedTypes = new Stack<>();
        int jsonFieldDepth = 0;
        Stack<Integer> arrayIndexes = new Stack<>();
        Stack<ParserContext> parserContexts = new Stack<>();

        StateMachine() {
            reset();
        }

        public void reset() {
            index = 0;
            currentJsonNode = null;
            line = 1;
            column = 0;
            nodesStack = new ArrayDeque<>();
            fieldNames = new ArrayDeque<>();
            fieldHierarchy.clear();
            currentField = null;
            restType.clear();
            expectedTypes.clear();
            jsonFieldDepth = 0;
            arrayIndexes.clear();
        }

        private static boolean isWhitespace(char ch) {
            return ch == SPACE || ch == HZ_TAB || ch == NEWLINE || ch == CR;
        }

        private static void throwExpected(String... chars) throws JsonParserException {
            throw new JsonParserException("expected '" + String.join("' or '", chars) + "'");
        }

        private void processLocation(char ch) {
            if (ch == '\n') {
                this.line++;
                this.column = 0;
            } else {
                this.column++;
            }
        }

        public Object execute(Reader reader, Type type) throws BError {
            switch (type.getTag()) {
                // TODO: Handle readonly and singleton type as expType.
                case TypeTags.RECORD_TYPE_TAG:
                    RecordType recordType = (RecordType) type;
                    expectedTypes.push(recordType);
                    fieldHierarchy.push(JsonCreator.getAllFieldsInRecord(recordType));
                    restType.push(recordType.getRestFieldType());
                    break;
                case TypeTags.ARRAY_TAG:
                case TypeTags.TUPLE_TAG:
                    expectedTypes.push(type);
                    arrayIndexes.push(0);
                    break;
                case TypeTags.NULL_TAG:
                case TypeTags.BOOLEAN_TAG:
                case TypeTags.INT_TAG:
                case TypeTags.FLOAT_TAG:
                case TypeTags.DECIMAL_TAG:
                case TypeTags.STRING_TAG:
                    expectedTypes.push(type);
                    break;
                case TypeTags.JSON_TAG:
                case  TypeTags.ANYDATA_TAG:
                    expectedTypes.push(type);
                    fieldHierarchy.push(new HashMap<>());
                    restType.push(type);
                    break;
                case TypeTags.MAP_TAG:
                    expectedTypes.push(type);
                    fieldHierarchy.push(new HashMap<>());
                    restType.push(((MapType) type).getConstrainedType());
                    break;
                case TypeTags.UNION_TAG:
                    if (isSupportedUnionType((UnionType) type)) {
                        expectedTypes.push(type);
                        break;
                    }
                    throw DiagnosticLog.error(DiagnosticErrorCode.UNSUPPORTED_TYPE, type);
                default:
                    throw DiagnosticLog.error(DiagnosticErrorCode.UNSUPPORTED_TYPE, type);
            }

            State currentState = DOC_START_STATE;
            try {
                char[] buff = new char[1024];
                int count;
                while ((count = reader.read(buff)) > 0) {
                    this.index = 0;
                    while (this.index < count) {
                        currentState = currentState.transition(this, buff, this.index, count);
                    }
                }
                currentState = currentState.transition(this, new char[] { EOF }, 0, 1);
                if (currentState != DOC_END_STATE) {
                    throw ErrorCreator.createError(StringUtils.fromString("invalid JSON document"));
                }
                return currentJsonNode;
            } catch (IOException e) {
                throw DiagnosticLog.error(DiagnosticErrorCode.JSON_READER_FAILURE, e.getMessage());
            } catch (JsonParserException e) {
                throw DiagnosticLog.error(DiagnosticErrorCode.JSON_PARSER_EXCEPTION, e.getMessage(), line, column);
            }
        }

        private boolean isSupportedUnionType(UnionType type) {
            for (Type memberType : type.getMemberTypes()) {
                switch (memberType.getTag()) {
                    case TypeTags.RECORD_TYPE_TAG:
                    case TypeTags.OBJECT_TYPE_TAG:
                    case TypeTags.MAP_TAG:
                    case TypeTags.JSON_TAG:
                    case TypeTags.ANYDATA_TAG:
                        return false;
                    case TypeTags.UNION_TAG:
                        return !isSupportedUnionType(type);
                }
            }
            return true;
        }

        private void append(char ch) {
            try {
                this.charBuff[this.charBuffIndex] = ch;
                this.charBuffIndex++;
            } catch (ArrayIndexOutOfBoundsException e) {
                /* this approach is faster than checking for the size by ourself */
                this.growCharBuff();
                this.charBuff[this.charBuffIndex++] = ch;
            }
        }

        private void growCharBuff() {
            char[] newBuff = new char[charBuff.length * 2];
            System.arraycopy(this.charBuff, 0, newBuff, 0, this.charBuff.length);
            this.charBuff = newBuff;
        }

        private State finalizeNonArrayObject() {
            if (jsonFieldDepth > 0) {
                jsonFieldDepth--;
            }

            if (!expectedTypes.isEmpty() && expectedTypes.peek() == null) {
                // Skip the value and continue to next state.
                parserContexts.pop();
                if (parserContexts.peek() == ParserContext.MAP) {
                    return FIELD_END_STATE;
                }
                return ARRAY_ELEMENT_END_STATE;
            }

            Map<String, Field> remainingFields = fieldHierarchy.pop();
            restType.pop();
            for (Field field : remainingFields.values()) {
                if (SymbolFlags.isFlagOn(field.getFlags(), SymbolFlags.REQUIRED)) {
                    throw DiagnosticLog.error(DiagnosticErrorCode.REQUIRED_FIELD_NOT_PRESENT, field.getFieldName());
                }
            }
            return finalizeObject();
        }

        private State finalizeObject() {
            // Skip the value and continue to next state.
            parserContexts.pop();

            if (!expectedTypes.isEmpty() && expectedTypes.peek() == null) {
                if (parserContexts.peek() == ParserContext.MAP) {
                    return FIELD_END_STATE;
                }
                return ARRAY_ELEMENT_END_STATE;
            }

            if (nodesStack.isEmpty()) {
                return DOC_END_STATE;
            }

            Object parentNode = nodesStack.pop();
            Type parentNodeType = TypeUtils.getType(parentNode);
            int parentNodeTypeTag = TypeUtils.getReferredType(parentNodeType).getTag();
            if (parentNodeTypeTag == TypeTags.RECORD_TYPE_TAG || parentNodeTypeTag == TypeTags.MAP_TAG) {
                currentJsonNode = parentNode;
                return FIELD_END_STATE;
            }

            switch (TypeUtils.getType(parentNode).getTag()) {
                case TypeTags.ARRAY_TAG:
                    // Handle projection in array.
                    ArrayType arrayType = (ArrayType) parentNodeType;
                    if (arrayType.getState() == ArrayType.ArrayState.CLOSED &&
                            arrayType.getSize() <= arrayIndexes.peek()) {
                        break;
                    }
                    ((BArray) parentNode).add(arrayIndexes.peek(), currentJsonNode);
                    break;
                case TypeTags.TUPLE_TAG:
                    ((BArray) parentNode).add(arrayIndexes.peek(), currentJsonNode);
                    break;
                default:
                    break;
            }

            currentJsonNode = parentNode;
            return ARRAY_ELEMENT_END_STATE;
        }

        public enum ParserContext {
            MAP,
            ARRAY
        }

        /**
         * A specific state in the JSON parsing state machine.
         */
        interface State {

            /**
             * Input given to the current state for a transition.
             *
             * @param sm the state machine
             * @param buff the input characters for the current state
             * @param i the location from the character should be read from
             * @param count the number of characters to read from the buffer
             * @return the new resulting state
             */
            State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException;
        }

        /**
         * Represents the JSON document start state.
         */
        private static class DocumentStartState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                char ch;
                State state = null;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == '{') {
                        sm.currentJsonNode = JsonCreator.initRootMapValue(sm.expectedTypes.peek());
                        sm.parserContexts.push(JsonParser.StateMachine.ParserContext.MAP);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        sm.parserContexts.push(JsonParser.StateMachine.ParserContext.ARRAY);
                        Type expType = sm.expectedTypes.peek();
                        // In this point we know rhs is json[] or anydata[] hence init index counter.
                        if (expType.getTag() == TypeTags.JSON_TAG || expType.getTag() == TypeTags.ANYDATA_TAG) {
                            sm.arrayIndexes.push(0);
                        }
                        sm.currentJsonNode = JsonCreator.initArrayValue(sm.expectedTypes.peek());
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                    } else if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == QUOTES) {
                        sm.currentQuoteChar = ch;
                        state = STRING_VALUE_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("empty JSON document");
                    } else {
                        state = NON_STRING_VALUE_STATE;
                    }
                    break;
                }
                if (state == NON_STRING_VALUE_STATE) {
                    sm.index = i;
                } else {
                    sm.index = i + 1;
                }
                return state;
            }
        }

        /**
         * Represents the JSON document end state.
         */
        private static class DocumentEndState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                char ch;
                State state = null;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch) || ch == EOF) {
                        state = this;
                        continue;
                    }
                    throw new JsonParserException("JSON document has already ended");
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state just before the first object field is defined.
         */
        private static class FirstFieldReadyState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                char ch;
                State state = null;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == QUOTES) {
                        state = FIELD_NAME_STATE;
                        sm.currentQuoteChar = ch;
                    } else if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == '}') {
                        state = sm.finalizeNonArrayObject();
                        sm.expectedTypes.pop();
                    } else {
                        StateMachine.throwExpected("\"", "}");
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state just before the first array element is defined.
         */
        private static class FirstArrayElementReadyState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == QUOTES) {
                        state = STRING_ARRAY_ELEMENT_STATE;
                        sm.currentQuoteChar = ch;
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                    } else if (ch == '{') {
                        // Get member type of the array and set as expected type.
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                        JsonCreator.updateNextMapValue(sm);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        // Get member type of the array and set as expected type.
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                        sm.arrayIndexes.push(0);
                        Optional<BArray> nextArray = JsonCreator.initNewArrayValue(sm);
                        nextArray.ifPresent(array -> sm.currentJsonNode = array);
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                    } else if (ch == ']') {
                        state = sm.finalizeObject();
                        sm.expectedTypes.pop();
                    } else {
                        state = NON_STRING_ARRAY_ELEMENT_STATE;
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                    }
                    break;
                }
                if (state == NON_STRING_ARRAY_ELEMENT_STATE) {
                    sm.index = i;
                } else {
                    sm.index = i + 1;
                }
                return state;
            }
        }

        /**
         * Represents the state just before a non-first object field is defined.
         */
        private static class NonFirstFieldReadyState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == QUOTES) {
                        sm.currentQuoteChar = ch;
                        state = FIELD_NAME_STATE;
                    } else if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else {
                        StateMachine.throwExpected("\"");
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state just before a non-first array element is defined.
         */
        private static class NonFirstArrayElementReadyState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == QUOTES) {
                        state = STRING_ARRAY_ELEMENT_STATE;
                        sm.currentQuoteChar = ch;
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                    } else if (ch == '{') {
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                        JsonCreator.updateNextMapValue(sm);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                        sm.arrayIndexes.push(0);
                        Optional<BArray> nextArray = JsonCreator.initNewArrayValue(sm);
                        nextArray.ifPresent(array -> sm.currentJsonNode = array);
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                    } else {
                        sm.expectedTypes.push(JsonCreator.getMemberType(sm.expectedTypes.peek(),
                                sm.arrayIndexes.peek()));
                        state = NON_STRING_ARRAY_ELEMENT_STATE;
                    }
                    break;
                }
                if (state == NON_STRING_ARRAY_ELEMENT_STATE) {
                    sm.index = i;
                } else {
                    sm.index = i + 1;
                }
                return state;
            }
        }

        private String value() {
            String result = new String(this.charBuff, 0, this.charBuffIndex);
            this.charBuffIndex = 0;
            return result;
        }

        private String processFieldName() {
            String value = this.value();
            return value;
        }

        /**
         * Represents the state during a field name.
         */
        private static class FieldNameState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                char ch;
                State state = null;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == sm.currentQuoteChar) {
                        String jsonFieldName = sm.processFieldName();
                        if (sm.jsonFieldDepth == 0) {
                            sm.currentField = sm.fieldHierarchy.peek().remove(jsonFieldName);
                            Type fieldType;
                            if (sm.currentField == null) {
                                fieldType = sm.restType.peek();
                            } else {
                                jsonFieldName = sm.currentField.getFieldName();
                                fieldType = sm.currentField.getFieldType();
                            }
                            sm.expectedTypes.push(fieldType);
                        } else if (sm.expectedTypes.peek() == null) {
                            sm.expectedTypes.push(null);
                        }
                        sm.fieldNames.push(jsonFieldName);
                        state = END_FIELD_NAME_STATE;
                    } else if (ch == REV_SOL) {
                        state = FIELD_NAME_ESC_CHAR_PROCESSING_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state where a field name definition has ended.
         */
        private static class EndFieldNameState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == ':') {
                        state = FIELD_VALUE_READY_STATE;
                    } else {
                        StateMachine.throwExpected(":");
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state where a field value is about to be defined.
         */
        private static class FieldValueReadyState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == QUOTES) {
                        state = STRING_FIELD_VALUE_STATE;
                        sm.currentQuoteChar = ch;
                    } else if (ch == '{') {
                        JsonCreator.updateNextMapValue(sm);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        sm.arrayIndexes.push(0);
                        Optional<BArray> nextArray = JsonCreator.initNewArrayValue(sm);
                        if (nextArray.isPresent()) {
                            sm.currentJsonNode = nextArray.get();
                            JsonCreator.updateRecordFieldValue(StringUtils.fromString(sm.fieldNames.peek()),
                                    sm.nodesStack.peek(), sm.currentJsonNode);
                        }
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                    } else {
                        state = NON_STRING_FIELD_VALUE_STATE;
                    }
                    break;
                }
                if (state == NON_STRING_FIELD_VALUE_STATE) {
                    sm.index = i;
                } else {
                    sm.index = i + 1;
                }
                return state;
            }
        }

        /**
         * Represents the state during a string field value is defined.
         */
        private static class StringFieldValueState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == sm.currentQuoteChar) {
                        String s = sm.value();
                        Type expType = sm.expectedTypes.pop();
                        if (expType == null) {
                            state = FIELD_END_STATE;
                            break;
                        }

                        if (sm.jsonFieldDepth > 0) {
                            sm.currentJsonNode = JsonCreator.convertAndUpdateCurrentJsonNode(sm,
                                    StringUtils.fromString(s), expType);
                        } else if (sm.currentField != null) {
                            sm.currentJsonNode = JsonCreator.convertAndUpdateCurrentJsonNode(sm,
                                    StringUtils.fromString(s), expType);
                        } else if (sm.restType.peek() != null) {
                            try {
                                sm.currentJsonNode = JsonCreator.convertAndUpdateCurrentJsonNode(sm,
                                        StringUtils.fromString(s), expType);
                                // this element will be ignored in projection
                            } catch (BError ignored) { }
                        }
                        state = FIELD_END_STATE;
                    } else if (ch == REV_SOL) {
                        state = STRING_FIELD_ESC_CHAR_PROCESSING_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state during a string array element is defined.
         */
        private static class StringArrayElementState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == sm.currentQuoteChar) {
                        sm.processValue();
                        state = ARRAY_ELEMENT_END_STATE;
                    } else if (ch == REV_SOL) {
                        state = STRING_AE_ESC_CHAR_PROCESSING_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state during a non-string field value is defined.
         */
        private static class NonStringFieldValueState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == '{') {
                        JsonCreator.updateNextMapValue(sm);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                        Optional<BArray> nextArray = JsonCreator.initNewArrayValue(sm);
                        nextArray.ifPresent(bArray -> sm.currentJsonNode = bArray);
                    } else if (ch == '}') {
                        sm.processValue();
                        state = sm.finalizeNonArrayObject();
                        sm.expectedTypes.pop();
                    } else if (ch == ']') {
                        sm.processValue();
                        state = sm.finalizeObject();
                        sm.expectedTypes.pop();
                    } else if (ch == ',') {
                        sm.processValue();
                        state = NON_FIRST_FIELD_READY_STATE;
                    } else if (StateMachine.isWhitespace(ch)) {
                        sm.processValue();
                        state = FIELD_END_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state during a non-string array element is defined.
         */
        private static class NonStringArrayElementState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == '{') {
                        JsonCreator.updateNextMapValue(sm);
                        state = FIRST_FIELD_READY_STATE;
                    } else if (ch == '[') {
                        state = FIRST_ARRAY_ELEMENT_READY_STATE;
                        Optional<BArray> nextArray = JsonCreator.initNewArrayValue(sm);
                        nextArray.ifPresent(bArray -> sm.currentJsonNode = bArray);
                    } else if (ch == ']') {
                        sm.processValue();
                        int currentIndex = sm.arrayIndexes.pop();
                        state = sm.finalizeObject();
                        JsonCreator.validateListSize(currentIndex, sm.expectedTypes.pop());
                    } else if (ch == ',') {
                        sm.processValue();
                        state = NON_FIRST_ARRAY_ELEMENT_READY_STATE;

                        // Update index of the array element
                        int arrayIndex = sm.arrayIndexes.pop();
                        sm.arrayIndexes.push(arrayIndex + 1);
                    } else if (StateMachine.isWhitespace(ch)) {
                        sm.processValue();
                        state = ARRAY_ELEMENT_END_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state during a string value is defined.
         */
        private static class StringValueState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (ch == sm.currentQuoteChar) {
                        sm.currentJsonNode = JsonCreator.convertAndUpdateCurrentJsonNode(sm,
                                StringUtils.fromString(sm.value()), sm.expectedTypes.peek());
                        state = DOC_END_STATE;
                    } else if (ch == REV_SOL) {
                        state = STRING_VAL_ESC_CHAR_PROCESSING_STATE;
                    } else if (ch == EOF) {
                        throw new JsonParserException("unexpected end of JSON document");
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        private void processValue() {
            Type expType = expectedTypes.pop();
            BString value = StringUtils.fromString(value());
            if (expType == null) {
                return;
            }
            currentJsonNode = JsonCreator.convertAndUpdateCurrentJsonNode(this, value, expType);
        }

        /**
         * Represents the state during a non-string value is defined.
         */
        private static class NonStringValueState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch) || ch == EOF) {
                        sm.currentJsonNode = null;
                        sm.processValue();
                        state = DOC_END_STATE;
                    } else {
                        sm.append(ch);
                        state = this;
                        continue;
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }
        }

        /**
         * Represents the state where an object field has ended.
         */
        private static class FieldEndState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == ',') {
                        state = NON_FIRST_FIELD_READY_STATE;
                    } else if (ch == '}') {
                        state = sm.finalizeNonArrayObject();
                        sm.expectedTypes.pop();
                    } else {
                        StateMachine.throwExpected(",", "}");
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }

        }

        /**
         * Represents the state where an array element has ended.
         */
        private static class ArrayElementEndState implements State {

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if (StateMachine.isWhitespace(ch)) {
                        state = this;
                        continue;
                    } else if (ch == ',') {
                        state = NON_FIRST_ARRAY_ELEMENT_READY_STATE;

                        // Update index of the array element
                        int arrayIndex = sm.arrayIndexes.pop();
                        sm.arrayIndexes.push(arrayIndex + 1);
                    } else if (ch == ']') {
                        int currentIndex = sm.arrayIndexes.pop();
                        state = sm.finalizeObject();
                        JsonCreator.validateListSize(currentIndex, sm.expectedTypes.pop());
                    } else {
                        StateMachine.throwExpected(",", "]");
                    }
                    break;
                }
                sm.index = i + 1;
                return state;
            }

        }

        /**
         * Represents the state where an escaped unicode character in hex format is processed
         * from a object string field.
         */
        private static class StringFieldUnicodeHexProcessingState extends UnicodeHexProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_FIELD_VALUE_STATE;
            }

        }

        /**
         * Represents the state where an escaped unicode character in hex format is processed
         * from an array string field.
         */
        private static class StringAEProcessingState extends UnicodeHexProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_ARRAY_ELEMENT_STATE;
            }

        }

        /**
         * Represents the state where an escaped unicode character in hex format is processed
         * from a string value.
         */
        private static class StringValueUnicodeHexProcessingState extends UnicodeHexProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_VALUE_STATE;
            }

        }

        /**
         * Represents the state where an escaped unicode character in hex format is processed
         * from a field name.
         */
        private static class FieldNameUnicodeHexProcessingState extends UnicodeHexProcessingState {

            @Override
            protected State getSourceState() {
                return FIELD_NAME_STATE;
            }

        }

        /**
         * Represents the state where an escaped unicode character in hex format is processed.
         */
        private abstract static class UnicodeHexProcessingState implements State {

            protected abstract State getSourceState();

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                for (; i < count; i++) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f')) {
                        sm.hexBuilder.append(ch);
                        if (sm.hexBuilder.length() >= 4) {
                            sm.append(this.extractUnicodeChar(sm));
                            this.reset(sm);
                            state = this.getSourceState();
                            break;
                        }
                        state = this;
                        continue;
                    }
                    this.reset(sm);
                    StateMachine.throwExpected("hexadecimal value of an unicode character");
                    break;
                }
                sm.index = i + 1;
                return state;
            }

            private void reset(StateMachine sm) {
                sm.hexBuilder.setLength(0);
            }

            private char extractUnicodeChar(StateMachine sm) {
                return StringEscapeUtils.unescapeJava("\\u" + sm.hexBuilder.toString()).charAt(0);
            }

        }

        /**
         * Represents the state where an escaped character is processed in a object string field.
         */
        private static class StringFieldEscapedCharacterProcessingState extends EscapedCharacterProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_FIELD_VALUE_STATE;
            }

        }

        /**
         * Represents the state where an escaped character is processed in an array string field.
         */
        private static class StringAEEscapedCharacterProcessingState extends EscapedCharacterProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_ARRAY_ELEMENT_STATE;
            }

        }

        /**
         * Represents the state where an escaped character is processed in a string value.
         */
        private static class StringValueEscapedCharacterProcessingState extends EscapedCharacterProcessingState {

            @Override
            protected State getSourceState() {
                return STRING_VALUE_STATE;
            }

        }

        /**
         * Represents the state where an escaped character is processed in a field name.
         */
        private static class FieldNameEscapedCharacterProcessingState extends EscapedCharacterProcessingState {

            @Override
            protected State getSourceState() {
                return FIELD_NAME_STATE;
            }

        }

        /**
         * Represents the state where an escaped character is processed.
         */
        private abstract static class EscapedCharacterProcessingState implements State {

            protected abstract State getSourceState();

            @Override
            public State transition(StateMachine sm, char[] buff, int i, int count) throws JsonParserException {
                State state = null;
                char ch;
                if (i < count) {
                    ch = buff[i];
                    sm.processLocation(ch);
                    switch (ch) {
                        case '"':
                            sm.append(QUOTES);
                            state = this.getSourceState();
                            break;
                        case '\\':
                            sm.append(REV_SOL);
                            state = this.getSourceState();
                            break;
                        case '/':
                            sm.append(SOL);
                            state = this.getSourceState();
                            break;
                        case 'b':
                            sm.append(BACKSPACE);
                            state = this.getSourceState();
                            break;
                        case 'f':
                            sm.append(FORMFEED);
                            state = this.getSourceState();
                            break;
                        case 'n':
                            sm.append(NEWLINE);
                            state = this.getSourceState();
                            break;
                        case 'r':
                            sm.append(CR);
                            state = this.getSourceState();
                            break;
                        case 't':
                            sm.append(HZ_TAB);
                            state = this.getSourceState();
                            break;
                        case 'u':
                            if (this.getSourceState() == STRING_FIELD_VALUE_STATE) {
                                state = STRING_FIELD_UNICODE_HEX_PROCESSING_STATE;
                            } else if (this.getSourceState() == STRING_VALUE_STATE) {
                                state = STRING_VALUE_UNICODE_HEX_PROCESSING_STATE;
                            } else if (this.getSourceState() == FIELD_NAME_STATE) {
                                state = FIELD_NAME_UNICODE_HEX_PROCESSING_STATE;
                            } else if (this.getSourceState() == STRING_ARRAY_ELEMENT_STATE) {
                                state = STRING_AE_PROCESSING_STATE;
                            } else {
                                throw new JsonParserException("unknown source '" + this.getSourceState() +
                                        "' in escape char processing state");
                            }
                            break;
                        default:
                            StateMachine.throwExpected("escaped characters");
                    }
                }
                sm.index = i + 1;
                return state;
            }

        }
    }
}
