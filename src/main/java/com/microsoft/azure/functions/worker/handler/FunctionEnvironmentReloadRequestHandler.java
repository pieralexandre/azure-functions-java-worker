package com.microsoft.azure.functions.worker.handler;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;

import com.microsoft.azure.functions.rpc.messages.*;
import com.microsoft.azure.functions.rpc.messages.FunctionEnvironmentReloadResponse.Builder;
import com.microsoft.azure.functions.worker.broker.JavaFunctionBroker;
import com.microsoft.azure.functions.worker.description.FunctionMethodDescriptor;

public class FunctionEnvironmentReloadRequestHandler
    extends MessageHandler<FunctionEnvironmentReloadRequest, FunctionEnvironmentReloadResponse.Builder> {
  public FunctionEnvironmentReloadRequestHandler(JavaFunctionBroker broker) {
    super(StreamingMessage::getFunctionEnvironmentReloadRequest,
        FunctionEnvironmentReloadResponse::newBuilder,
        FunctionEnvironmentReloadResponse.Builder::setResult,
        StreamingMessage.Builder::setFunctionEnvironmentReloadResponse);

    this.broker = broker;    
  }

  public Map<String, String> EnvironmentVariables = new HashMap<>();

  @Override
  String execute(FunctionEnvironmentReloadRequest request, Builder response) throws Exception {
    EnvironmentVariables = request.getEnvironmentVariablesMap();
    if (EnvironmentVariables == null || EnvironmentVariables.isEmpty()) {    
      return String.format("Ignoring FunctionEnvironmentReloadRequest as newSettings map is either empty or null");
    }
    
    setEnv(EnvironmentVariables);    
    return String.format("FunctionEnvironmentReloadRequest completed");
  }

  FunctionMethodDescriptor createFunctionDescriptor(String functionId,
      RpcFunctionMetadata metadata) {
    return new FunctionMethodDescriptor(functionId, metadata.getName(), metadata.getEntryPoint(),
        metadata.getScriptFile());
  }
  
  /*
   * This is a helper utility specifically to reload environment variables 
   * if java language worker is started in standby mode by the functions runtime
   * and should not be used for other purposes
   */
  public void setEnv(Map<String, String> newSettings) throws Exception {
    if (newSettings == null || newSettings.isEmpty()) {    
      return;
    }

    for (Map.Entry<String,String> entry : newSettings.entrySet()) {
        Kernel32.INSTANCE.SetEnvironmentVariable(entry.getKey(), entry.getValue());
      }

      Kernel32.INSTANCE.SetEnvironmentVariable("WEBSITE_PLACEHOLDER_MODE", null);
      Kernel32.INSTANCE.SetEnvironmentVariable("WEBSITE_PLACEHOLDER_MODE", "0");

      PicoHelper picoHelperInstance = PicoHelper.INSTANCE;

      picoHelperInstance.ReinitializeDetours();

      try {
        Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
        Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
        theEnvironmentField.setAccessible(true);
        Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
        env.clear();
        env.putAll(newSettings);
        Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
        theCaseInsensitiveEnvironmentField.setAccessible(true);
        Map<String, String> cienv = (Map<String, String>)     theCaseInsensitiveEnvironmentField.get(null);
        cienv.clear();
        cienv.putAll(newSettings);
      } catch (NoSuchFieldException e) {
        Class[] classes = Collections.class.getDeclaredClasses();
        Map<String, String> env = System.getenv();
        for(Class cl : classes) {
          if("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
            Field field = cl.getDeclaredField("m");
            field.setAccessible(true);
            Object obj = field.get(env);
            Map<String, String> map = (Map<String, String>) obj;
            map.clear();
            map.putAll(newSettings);
          }
        }
      }
    }
	
  private final JavaFunctionBroker broker;
}
