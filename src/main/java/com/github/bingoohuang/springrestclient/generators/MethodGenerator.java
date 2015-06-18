package com.github.bingoohuang.springrestclient.generators;

import com.alibaba.fastjson.JSON;
import com.github.bingoohuang.springrestclient.annotations.SuccInResponseJSONProperty;
import com.github.bingoohuang.springrestclient.provider.BaseUrlProvider;
import com.github.bingoohuang.springrestclient.provider.SignProvider;
import com.github.bingoohuang.springrestclient.utils.Futures;
import com.github.bingoohuang.springrestclient.utils.RestReq;
import com.github.bingoohuang.springrestclient.utils.RestReqBuilder;
import com.google.common.primitives.Primitives;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;

import static com.github.bingoohuang.springrestclient.utils.Asms.*;
import static com.github.bingoohuang.springrestclient.utils.PrimitiveWrappers.getParseXxMethodName;
import static org.objectweb.asm.Opcodes.*;


public class MethodGenerator {
    public static final String StatusExceptionMappings = "StatusExceptionMappings";
    public static final String FixedRequestParams = "FixedRequestParams";
    public static final String SuccInResponseJSONProperty = "SuccInResponseJSONProperty";
    public static final String baseUrlProvider = "baseUrlProvider";
    public static final String signProvider = "signProvider";

    private final Method method;
    private final MethodVisitor mv;
    private final Annotation[][] annotations;
    private final int paramSize;
    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;
    private final int offsetSize;
    private final String classRequestMapping;
    private final RequestMapping requestMapping;
    private final String implName;
    private final boolean futureReturnType;
    private final boolean isBinaryReturnType;

    public MethodGenerator(ClassWriter classWriter, String implName, Method method, String classRequestMapping) {
        this.implName = implName;
        this.method = method;
        this.mv = visitMethod(method, classWriter);
        this.annotations = method.getParameterAnnotations();
        this.parameterTypes = method.getParameterTypes();
        this.paramSize = annotations.length;
        this.offsetSize = computeOffsetSize();
        returnType = method.getReturnType();
        this.classRequestMapping = classRequestMapping;
        this.requestMapping = method.getAnnotation(RequestMapping.class);
        this.futureReturnType = Futures.isFutureReturnType(method);
        this.isBinaryReturnType = returnType == InputStream.class;
    }

    private MethodVisitor visitMethod(Method method, ClassWriter classWriter) {
        String methodDescriptor = Type.getMethodDescriptor(method);
        return classWriter.visitMethod(ACC_PUBLIC, method.getName(), methodDescriptor, null, null);
    }

    private int computeOffsetSize() {
        int cnt = 0;
        for (Class<?> parameterType : parameterTypes) {
            if (isWideType(parameterType)) ++cnt;
        }

        return paramSize + cnt;
    }

    public void generate() {
        start();
        body();
        end();
    }

    private void body() {
        createMap(1, PathVariable.class);
        createMap(2, RequestParam.class);

        buildUniRestReq();

        request();

        dealResult();
    }

    private void dealResult() {
        if (returnType == void.class) {
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            return;
        }

        if (returnType.isPrimitive()) {
            primitiveValueOfAndReturn();
        } else {
            objectValueOfAndReturn();
        }
    }

    private void request() {
        mv.visitVarInsn(ASTORE, offsetSize + 3);
        mv.visitVarInsn(ALOAD, offsetSize + 3);

        if (isPostMethodOrNone()) {
            int requestBodyOffset = findRequestBodyParameterOffset();
            if (requestBodyOffset > -1) {
                mv.visitVarInsn(ALOAD, requestBodyOffset + 1);
                getOrPost(futureReturnType,
                        "postAsJsonAsync", sig(Future.class, Object.class),
                        "postAsJson", sig(String.class, Object.class),
                        "postAsJsonBinary", sig(InputStream.class, Object.class));
            } else {
                getOrPost(futureReturnType,
                        "postAsync", sig(Future.class),
                        "post", sig(String.class),
                        "postBinary", sig(InputStream.class));
            }
        } else if (isGetMethod()) {
            getOrPost(futureReturnType,
                    "getAsync", sig(Future.class),
                    "get", sig(String.class),
                    "getBinary", sig(InputStream.class));
        }
    }

    private void getOrPost(boolean futureReturnType, String getAsync, String asyncSig,
                           String sync, String syncSig,
                           String syncBinary, String syncSigBinary) {
        if (futureReturnType) {
            mv.visitMethodInsn(INVOKEVIRTUAL, p(RestReq.class), getAsync, asyncSig, false);
        } else if (isBinaryReturnType) {
            mv.visitMethodInsn(INVOKEVIRTUAL, p(RestReq.class), syncBinary, syncSigBinary, false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, p(RestReq.class), sync, syncSig, false);
        }
    }

    private void buildUniRestReq() {
        String impl = p(implName);

        String restReqBuilder = p(RestReqBuilder.class);
        mv.visitTypeInsn(NEW, restReqBuilder);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, restReqBuilder, "<init>", "()V", false);
        mv.visitLdcInsn(getFullRequestMapping());
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "prefix", sigRest(String.class), false);
        mv.visitInsn(futureReturnType ? ICONST_1 : ICONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "async", sigRest(boolean.class), false);
        mv.visitLdcInsn(Type.getType(method.getDeclaringClass()));
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "apiClass", sigRest(Class.class), false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, impl, baseUrlProvider, ci(BaseUrlProvider.class));
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, baseUrlProvider, sigRest(BaseUrlProvider.class), false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, impl, signProvider, ci(SignProvider.class));
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, signProvider, sigRest(SignProvider.class), false);

        mv.visitVarInsn(ALOAD, 0);
        String methodName = method.getName();
        mv.visitFieldInsn(GETFIELD, impl, methodName + SuccInResponseJSONProperty, ci(SuccInResponseJSONProperty.class));
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "succInResponseJSONProperty",
                sigRest(SuccInResponseJSONProperty.class), false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, impl, methodName + StatusExceptionMappings, ci(Map.class));
        String sigMap = sigRest(Map.class);
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "statusExceptionMappings", sigMap, false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, impl, methodName + FixedRequestParams, ci(Map.class));
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "fixedRequestParams", sigMap, false);

        mv.visitVarInsn(ALOAD, offsetSize + 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "routeParams", sigMap, false);

        mv.visitVarInsn(ALOAD, offsetSize + 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "requestParams", sigMap, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, restReqBuilder, "build", sig(RestReq.class), false);
    }

    private String sigRest(Class<?> clazz) {
        return sig(RestReqBuilder.class, clazz);
    }

    private String getFullRequestMapping() {
        boolean isEmpty = requestMapping != null && requestMapping.value().length > 0;
        String methodMappingName = isEmpty ? requestMapping.value()[0] : "";

        return classRequestMapping + methodMappingName;
    }

    private int findRequestBodyParameterOffset() {
        for (int i = 0, incr = 0; i < paramSize; i++) {
            if (isWideType(parameterTypes[i])) ++incr;

            for (Annotation annotation : annotations[i]) {
                if (annotation.annotationType() == RequestBody.class) {
                    return i + incr;
                }
            }
        }

        return -1;
    }

    private boolean isWideType(Class<?> parameterType) {
        return parameterType == long.class || parameterType == double.class;
    }

    private boolean isGetMethod() {
        if (requestMapping == null) return false;

        RequestMethod[] method = requestMapping.method();
        return method.length == 1 && method[0] == RequestMethod.GET;
    }

    private boolean isPostMethodOrNone() {
        if (requestMapping == null) return true;

        RequestMethod[] method = requestMapping.method();
        if (method.length == 0) return true;

        return method.length == 1 && method[0] == RequestMethod.POST;
    }

    private void objectValueOfAndReturn() {
        if (returnType == String.class || returnType == Object.class || isBinaryReturnType) {
            mv.visitInsn(ARETURN);
            return;
        }

        if (futureReturnType) {
            java.lang.reflect.Type futureType = Futures.getFutureGenericArgClass(method);
            if (!(futureType instanceof Class)) {
                mv.visitInsn(ARETURN);
                return;
            }

            mv.visitLdcInsn(Type.getType((Class) futureType));
            mv.visitVarInsn(ALOAD, offsetSize + 3);
            mv.visitMethodInsn(INVOKESTATIC, p(Futures.class),
                    futureType == Void.class ? "convertFutureVoid" : "convertFuture",
                    sig(Future.class, Future.class, Class.class, RestReq.class), false);
        } else {
            mv.visitLdcInsn(Type.getType(returnType));
            mv.visitMethodInsn(INVOKESTATIC, p(JSON.class), "parseObject",
                    sig(Object.class, String.class, Class.class), false);

        }
        mv.visitTypeInsn(CHECKCAST, p(returnType));
        mv.visitInsn(ARETURN);
    }

    private void primitiveValueOfAndReturn() {
        Class<?> wrapped = Primitives.wrap(returnType);
        mv.visitMethodInsn(INVOKESTATIC, p(wrapped), getParseXxMethodName(returnType),
                sig(returnType, String.class), false);

        mv.visitInsn(Type.getType(returnType).getOpcode(IRETURN));
    }

    private <T extends Annotation> void createMap(int index, Class<T> annotationClass) {
        mv.visitTypeInsn(NEW, p(LinkedHashMap.class));
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, p(LinkedHashMap.class), "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, offsetSize + index);

        for (int i = 0, incr = 0; i < paramSize; i++) {
            for (Annotation annotation : annotations[i]) {
                if (annotation.annotationType() != annotationClass) continue;

                mv.visitVarInsn(ALOAD, offsetSize + index);
                mv.visitLdcInsn(AnnotationUtils.getValue(annotation));
                wrapPrimitive(parameterTypes[i], i, incr);

                if (isWideType(parameterTypes[i])) ++incr;

                mv.visitMethodInsn(INVOKEVIRTUAL, p(LinkedHashMap.class), "put",
                        sig(Object.class, Object.class, Object.class), false);
                mv.visitInsn(POP);
            }
        }
    }

    private void wrapPrimitive(Class<?> type, int paramIndex, int incr) {
        Type parameterAsmType = Type.getType(type);
        int opcode = parameterAsmType.getOpcode(Opcodes.ILOAD);
        mv.visitVarInsn(opcode, paramIndex + 1 + incr);

        if (!type.isPrimitive()) return;

        Class<?> wrapped = Primitives.wrap(type);
        mv.visitMethodInsn(INVOKESTATIC, p(wrapped), "valueOf", sig(wrapped, type), false);
    }

    private void start() {
        mv.visitCode();
    }

    private void end() {
        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }
}
