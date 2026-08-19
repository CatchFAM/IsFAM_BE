package com.isfam.common.security;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class DeviceIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(DeviceId.class)
                && (parameter.getParameterType().equals(Long.class)
                || parameter.getParameterType().equals(long.class));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        DeviceId annotation = parameter.getParameterAnnotation(DeviceId.class);
        boolean required = annotation == null || annotation.required();

        String raw = webRequest.getHeader(DeviceId.HEADER);
        if (!StringUtils.hasText(raw)) {
            if (required || parameter.getParameterType().isPrimitive()) {
                throw new BusinessException(ErrorCode.DEVICE_002);
            }
            return null;
        }

        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.DEVICE_002);
        }
    }
}