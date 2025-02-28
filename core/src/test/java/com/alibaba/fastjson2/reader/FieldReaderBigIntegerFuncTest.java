package com.alibaba.fastjson2.reader;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONSchemaValidException;
import com.alibaba.fastjson2.annotation.JSONField;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FieldReaderBigIntegerFuncTest {
    @Test
    public void test() {
        Bean bean = new Bean();
        ObjectReader objectReader = ObjectReaderCreatorLambda.INSTANCE.createObjectReader(Bean.class);
        FieldReader fieldReader = objectReader.getFieldReader("value");
        fieldReader.accept(bean, "123");
        assertEquals(new BigInteger("123"), bean.value);

        assertThrows(JSONException.class, () -> fieldReader.accept(bean, new Object()));
    }

    public static class Bean {
        private BigInteger value;

        public BigInteger getValue() {
            return value;
        }

        public void setValue(BigInteger value) {
            this.value = value;
        }
    }

    @Test
    public void test1() {
        Bean1 bean = new Bean1();
        ObjectReader objectReader = ObjectReaderCreatorLambda.INSTANCE.createObjectReader(Bean1.class);
        FieldReader fieldReader = objectReader.getFieldReader("value");
        assertThrows(JSONSchemaValidException.class, () -> fieldReader.accept(bean, "123"));
        assertThrows(JSONSchemaValidException.class, () -> fieldReader.accept(bean, 123));
        assertThrows(JSONSchemaValidException.class, () -> fieldReader.accept(bean, 123L));
    }

    public static class Bean1 {
        @JSONField(schema = "{'minimum':128}")
        private BigInteger value;

        public BigInteger getValue() {
            return value;
        }

        public void setValue(BigInteger value) {
            this.value = value;
        }
    }

    @Test
    public void test2() {
        Bean2 bean = new Bean2();
        ObjectReader objectReader = ObjectReaderCreatorLambda.INSTANCE.createObjectReader(Bean2.class);
        FieldReader fieldReader = objectReader.getFieldReader("value");
        assertThrows(UnsupportedOperationException.class, () -> fieldReader.accept(bean, "123"));
        assertThrows(UnsupportedOperationException.class, () -> fieldReader.accept(bean, 123));
        assertThrows(UnsupportedOperationException.class, () -> fieldReader.accept(bean, 123L));
    }

    public static class Bean2 {
        public void setValue(BigInteger value) {
            throw new UnsupportedOperationException();
        }
    }
}
