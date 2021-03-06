/*
* Copyright (C) 2017 ChenFei, All Rights Reserved
*
* This program is free software; you can redistribute it and/or modify it 
* under the terms of the GNU General Public License as published by the Free 
* Software Foundation; either version 3 of the License, or (at your option) 
* any later version.
*
* This program is distributed in the hope that it will be useful, but 
* WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY 
* or FITNESS FOR A PARTICULAR PURPOSE. 
* See the GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License along with
* this program; if not, see <http://www.gnu.org/licenses>.
*
* This code is available under licenses for commercial use. Please contact
* ChenFei for more information.
*
* http://www.gplgpu.com
* http://www.chenfei.me
*
* Title       :  Spring DDAL
* Author      :  Chen Fei
* Email       :  cn.fei.chen@qq.com
*
*/
package io.isharing.springddal.route;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.isharing.springddal.datasource.DynamicDataSourceHolder;
import io.isharing.springddal.route.annotation.Router;
import io.isharing.springddal.route.exception.ParamsErrorException;
import io.isharing.springddal.route.rule.conf.TableRule;
import io.isharing.springddal.route.rule.conf.XMLLoader;
import io.isharing.springddal.route.rule.utils.ParameterMapping;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.BooleanMemberValue;
import javassist.bytecode.annotation.StringMemberValue;

/**
 * 切面切点 在Router注解的方法执行前执行 切点织入
 * 
 * @author <a href=mailto:cn.fei.chen@qq.com>Chen Fei</a>
 * 
 */
@Component
public class RouteInterceptor {

	private static final Logger log = LoggerFactory.getLogger(RouteInterceptor.class);

	private static ConcurrentHashMap<String, Boolean> methodIsReadCache = new ConcurrentHashMap<String, Boolean>();
	private RouteStrategy routeStrategy;

	public void routePoint() {}

	public Object doRoute(ProceedingJoinPoint jp) throws Throwable {
		long t1 = System.currentTimeMillis();
		Object result = null;
		Router router = getDeclaringClassAnnotation(jp);
		if (null == router) {
			log.error(">>> No Router annotation, use default node for query.");
			routeStrategy.routeToGlobalNode(false, true);
		} else {
			boolean isRoute = router.isRoute();
			String type = router.type();
			String dataNode = router.dataNode();
			String ruleName = router.ruleName();
			boolean readOnly = router.readOnly();
			boolean forceReadOnMaster = router.forceReadOnMaster();

			Method method = ((MethodSignature) jp.getSignature()).getMethod();
			Object target = jp.getTarget();
			String cacheKey = target.getClass().getName() + "." + method.getName();
			Boolean isReadCacheValue = methodIsReadCache.get(cacheKey);
			if (isReadCacheValue == null) {
				// 重新获取方法，否则传递的是接口的方法信息
				Method realMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());
				isReadCacheValue = isChoiceReadDB(realMethod, readOnly);
				methodIsReadCache.put(cacheKey, isReadCacheValue);
			}

			if (StringUtils.isBlank(dataNode) || StringUtils.isBlank(ruleName)) {
				String logmsg = ">>> DataNode and RuleName is NULL.";
				log.error(logmsg);
				throw new ParamsErrorException(logmsg);
			}
			if (isRoute) {// 路由计算
				log.debug(">>> calculating route...");
				if (StringUtils.isNotBlank(type) && type.equalsIgnoreCase("global")) {
					// 路由到全局库 根据注解参数做读写分离
					log.error(">> route to global datanode...");
					routeStrategy.routeToGlobalNode(isReadCacheValue, forceReadOnMaster);
				} else {
					execute(jp, router, ruleName, isReadCacheValue);
				}
			} else {
				log.error(">>> isRoute is not config on Router, using default node config on Rules.xml.");
				routeStrategy.routeToDefaultNode(ruleName, readOnly, forceReadOnMaster);// 根据注解参数做读写分离，如参数不足，则直接读写主库
			}
		}

		try {
			result = jp.proceed();
		} finally {
			DynamicDataSourceHolder.reset();
		}
		log.error(">>> doRoute time cost: " + (System.currentTimeMillis() - t1));
		return result;
	}

	/**
	 * 根据拆分字段routeField和拆分字段值routeFieldValue进入规则库进行路由计算
	 * 
	 * @param jp
	 *            ProceedingJoinPoint对象
	 * @param router
	 *            Router注解对象
	 * @param isReadCacheValue
	 *            是否读操作，boolean值
	 * @throws Throwable
	 */
	private void execute(ProceedingJoinPoint jp, Router router, String ruleName, boolean isReadCacheValue)
			throws Throwable {
		long t1 = System.currentTimeMillis();
		TableRule tableRule = XMLLoader.getTableRuleByRuleName(ruleName);
		if (null == tableRule) {
			String logmsg = ">>> FATAL ERROR! RuleName is not exist, please check your Router annotation configuration.";
			log.error(logmsg);
			throw new ParamsErrorException(logmsg);
		}
		String routeField = tableRule.getRouteColumn();
		log.debug(">>> ruleName=" + ruleName + ", routeField=" + routeField);
		Object[] args = jp.getArgs();
		Map<String, Object> nameAndArgs = getFieldsNameAndArgs(jp, args);
		log.debug(">>> " + nameAndArgs.toString());

		String routeFieldValue = processRouteFieldValue(args, nameAndArgs, routeField);
		if (StringUtils.isBlank(routeFieldValue)) {
			log.error(">>> routeFieldValue is NULL, query from default node(" + tableRule.getDefaultNode() + ") which defined in rules.xml(" + tableRule.getName() + ").");
			boolean forceReadOnMaster = router.forceReadOnMaster();
			routeStrategy.routeToDefaultNode(ruleName, isReadCacheValue, forceReadOnMaster);
			return;
		}
		// 进入规则库进行路由计算
		routeStrategy.route(router, routeField, routeFieldValue, isReadCacheValue);
		log.error(">>> execute time cost: " + (System.currentTimeMillis() - t1));
	}

	private String processRouteFieldValue(Object[] args, Map<String, Object> nameAndArgs, String routeField)
			throws Throwable {
		String routeFieldValue = "";
		if (args != null && args.length > 0) {
			for (int i = 0; i < args.length; i++) {
				long t2 = System.currentTimeMillis();
				if(null != args[i]){
					/**
					 * MyBatis的传入参数parameterType类型分两种 1.
					 * 基本数据类型：int,string,long,Date; 2. 复杂数据类型：类和Map
					 */
					if (ParameterMapping.isPrimitiveType(args[i].getClass())) {// 基本数据类型：int,string,long,Date
					/*
					 * !!CAUTION!! MyBatis中SQL语句传值是基本类型的情况，如果没有拆分字段则会无法匹配拆分字段的值。
					 * 规避方法为都用对象传值（如Map或实体对象），以下为另外一种临时解决方法。
					 */
					if (null != nameAndArgs && nameAndArgs.size() > 0) {
						Object objValue = nameAndArgs.get(routeField);
						if (null != objValue) {
							/**
							 * 要求：传递的值为基本类型时，函数的参数的名称必须与字段的名称一致.
							 * 比如rules.xml定义的拆分字段为userName，那么对应的方法名比如queryByUserName(String
							 * userName)， 参数userName与拆分字段必须保持一致，即为userName
							 */
							routeFieldValue = objValue.toString();
							log.error(">>> parameters is primitive type, routeField=" + routeField
									+ ", routeFieldValue=" + routeFieldValue);
							break;
						}
					}
					// routeFieldValue = args[i].toString();
				} else {// 复杂数据类型：类和Map，以及collection（List、Array...）
					if (args[i] instanceof List || args[i].getClass().isArray()) {
						/**
						 * 这里有两种情况：
						 * 1）带分片键的；
						 * 2）不带分片键的 带分片键的：比如 id in (...) 之类的
						 * 	  不带分片键的：比如 name in (...) 之类的 
						 * TODO 这两种情况，应该是按切分字段到相应节点查询或全库扫描 
						 * 但是目前全库扫描和跨库查询过于复杂，暂时不支持，对于这种情况，会直接路由至默认库上查询。
						 */
					} else {
						routeFieldValue = BeanUtils.getProperty(args[i], routeField);
						break;
					}
				}
				log.debug(
						">>> routeFieldValue=" + routeFieldValue + ", cost time=" + (System.currentTimeMillis() - t2));
				}
			}
		}
		return routeFieldValue;
	}

	/**
	 * 判断是否只读方法
	 * 
	 * 这里有两种途径判断是否读操作： 1）通过事务注解 <code>@Transactional</code> 2）通过Router注解配置
	 * <code>@Router</code>
	 * 
	 * @param method
	 *            执行方法
	 * @param readOnly
	 *            注解中配置的是否读操作
	 * @return 当前方法是否只读
	 */
	private boolean isChoiceReadDB(Method method, boolean readOnly) {
		Transactional transactionalAnno = AnnotationUtils.findAnnotation(method, Transactional.class);
		// 如果之前选择了写库，则现在还选择写库
		if (DynamicDataSourceHolder.isChoiceWrite()) {
			return false;
		}
		// 如果有事务注解，并且事务注解表明readOnly为false，那么判断为写操作。
		// 如果Router注解中也配置了readOnly=true，则以事务注解为准。
		if (null != transactionalAnno && !transactionalAnno.readOnly()) {
			return false;
		}
		return readOnly;
	}

	/**
	 * 获取自定义注解Router对象
	 * 
	 * @param jp
	 *            ProceedingJoinPoint
	 * @return
	 * @throws NoSuchMethodException
	 */
	private Router getDeclaringClassAnnotation(ProceedingJoinPoint jp) throws NoSuchMethodException {
		Router annotation = null;
		try {
			if(!hasTargetAnnotation(jp)){
				return null;
			}
			Method method = getMethod(jp);
            ClassPool classPool = ClassPool.getDefault();
            classPool.appendClassPath(new ClassClassPath(RouteInterceptor.class));
//            classPool.importPackage("io.isharing.springddal");
            
            CtClass clazz = null;
            try{
            	clazz = classPool.get(method.getDeclaringClass().getName());
            }catch(NotFoundException ne){
                Class<?> interfaceDao = getProxyDaoInterfaceClazz(jp);
                clazz = classPool.get(interfaceDao.getName());
                log.error(">>> proxy dao interface class. clazz name: "+interfaceDao.getName()+", method name: "+method.getName());
                method = interfaceDao.getMethod(method.getName(), method.getParameterTypes());
            }

			ClassFile classFile = clazz.getClassFile();
			log.debug(">>> Before merge value, Router:" + clazz.getAnnotation(Router.class));

			ConstPool constPool = classFile.getConstPool();
			Annotation tableAnnotation = new Annotation(Router.class.getName(), constPool);

			Router router = AnnotationUtils.findAnnotation(method.getDeclaringClass(), Router.class);
			if (router == null) {
				router = AnnotationUtils.findAnnotation(jp.getTarget().getClass(), Router.class);
			}
			if (router != null) {
				updateRouter(null, router, tableAnnotation, constPool);
			}
			boolean flag = method.isAnnotationPresent(Router.class);
			log.error(">>> Method isAnnotationPresent flag is " + flag);
			if (flag) {
				Router _router = method.getAnnotation(Router.class);
				updateRouter(router, _router, tableAnnotation, constPool);
			}

			// 获取运行时注解属性
			AnnotationsAttribute attribute = (AnnotationsAttribute) classFile
					.getAttribute(AnnotationsAttribute.visibleTag);
			if (null == attribute) {
				attribute = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
			}
			attribute.addAnnotation(tableAnnotation);
			classFile.addAttribute(attribute);
			classFile.setVersionToJava5();

			log.debug(">>> After merge value, Router:" + clazz.getAnnotation(Router.class));

			annotation = (Router) clazz.getAnnotation(Router.class);
		} catch (Exception e) {
			log.error(">>> occur exception, e:" + e.getLocalizedMessage());
			e.printStackTrace();
		}
		log.error(">>> annotation is " + annotation);
		return annotation;
	}

    /**
     * Has target annotation boolean.
     * 判断切入点的方法,类或接口是否被 @Router 注解标识
     * @param jp the jp
     * @return the boolean
     * @throws NoSuchMethodException the no such method exception
     */
    private boolean hasTargetAnnotation(ProceedingJoinPoint jp) throws NoSuchMethodException {
		Method method = getMethod(jp);
		boolean flag = method.isAnnotationPresent(Router.class);
		if(!flag){
			Class<?> cl = getClass(jp);
			Router annotation =  cl.getAnnotation(Router.class);
			if(annotation == null){
				Class<?>[] in = cl.getInterfaces();
				Class<?> interfaceDao = in[0];
				annotation = interfaceDao.getAnnotation(Router.class);
				if(annotation == null){
					return false;
				}
			}
		}
		return true;
	}

	private Class<?> getProxyDaoInterfaceClazz(ProceedingJoinPoint jp) throws NoSuchMethodException {
		Class<?> cl = getClass(jp);
		Class<?>[] in = cl.getInterfaces();
		Class<?> interfaceDao = in[0];
		return interfaceDao;
	}

	private void updateRouter(Router oldRouter, Router newRouter, Annotation tableAnnotation, ConstPool constPool) {
		boolean _isRoute = true;
		String _type = "";
		String _dataNode = "";
		String _ruleName = "";
		boolean _forceReadOnMaster = false;
		boolean _readOnly = true;
		if (null != oldRouter) {
			_isRoute = oldRouter.isRoute();
			_type = oldRouter.type();
			_dataNode = oldRouter.dataNode();
			_ruleName = oldRouter.ruleName();
			_readOnly = oldRouter.readOnly();
			_forceReadOnMaster = oldRouter.forceReadOnMaster();
		}

		if (newRouter != null) {
			boolean isRoute = newRouter.isRoute();
			String type = newRouter.type();
			String dataNode = newRouter.dataNode();
			String ruleName = newRouter.ruleName();
			boolean readOnly = newRouter.readOnly();
			boolean forceReadOnMaster = newRouter.forceReadOnMaster();

			if (_isRoute != isRoute) {
				tableAnnotation.addMemberValue("isRoute", new BooleanMemberValue(isRoute, constPool));
			}
			if (StringUtils.isNotBlank(dataNode) && !dataNode.equalsIgnoreCase(_dataNode)) {
				tableAnnotation.addMemberValue("dataNode", new StringMemberValue(dataNode, constPool));
			}
			if (StringUtils.isNotBlank(ruleName) && !ruleName.equalsIgnoreCase(_ruleName)) {
				tableAnnotation.addMemberValue("ruleName", new StringMemberValue(ruleName, constPool));
			}
			if (StringUtils.isNotBlank(type) && !type.equalsIgnoreCase(_type)) {
				tableAnnotation.addMemberValue("type", new StringMemberValue(type, constPool));
			}
			if (_readOnly != readOnly) {
				tableAnnotation.addMemberValue("readOnly", new BooleanMemberValue(readOnly, constPool));
			}
			if (_forceReadOnMaster != forceReadOnMaster) {
				tableAnnotation.addMemberValue("forceReadOnMaster",
						new BooleanMemberValue(forceReadOnMaster, constPool));
			}
		}
	}

	private Method getMethod(ProceedingJoinPoint jp) throws NoSuchMethodException {
		MethodSignature msig = (MethodSignature) jp.getSignature();
		return getClass(jp).getMethod(msig.getName(), msig.getParameterTypes());
	}

	private Class<? extends Object> getClass(ProceedingJoinPoint jp) throws NoSuchMethodException {
		return jp.getTarget().getClass();
	}

	private CtClass getCtClass(ProceedingJoinPoint jp) throws NoSuchMethodException, NotFoundException {
		Method method = getMethod(jp);
		ClassPool classPool = ClassPool.getDefault();
		classPool.appendClassPath(new ClassClassPath(RouteInterceptor.class));
		// classPool.importPackage("io.isharing.springddal");

		CtClass clazz = null;
		try {
			clazz = classPool.get(method.getDeclaringClass().getName());
		} catch (NotFoundException ne) {
			Class<?> interfaceDao = getProxyDaoInterfaceClazz(jp);
			clazz = classPool.get(interfaceDao.getName());
			log.error(">>> proxy dao interface class. clazz name: " + interfaceDao.getName());
		}
		return clazz;
	}

	/**
	 * 获取参数名和参数值，以Map形式返回 参数名为调用的函数的参数名。
	 * 
	 * @param jp
	 * @param args
	 * @return
	 * @throws NotFoundException
	 * @throws ClassNotFoundException
	 * @throws NoSuchMethodException
	 */
	private Map<String, Object> getFieldsNameAndArgs(ProceedingJoinPoint jp, Object[] args)
			throws NotFoundException, ClassNotFoundException, NoSuchMethodException {
    	String classType = jp.getTarget().getClass().getName();    
        Class<?> clazz = Class.forName(classType);    
        String clazzName = clazz.getName();
        String methodName = jp.getSignature().getName();
//        Map<String, Object> nameAndArgs = getFieldsNameAndArgs(this.getClass(), clazzName, methodName, args);
        
        Map<String,Object > nameAndArgs = new HashMap<String,Object>();
        
        ClassPool classPool = ClassPool.getDefault();
        classPool.appendClassPath(new ClassClassPath(RouteInterceptor.class));
//        classPool.importPackage("io.isharing.springddal");
        CtClass cc = null;
        try{
        	cc = classPool.get(clazzName);
        }catch(NotFoundException ne){
            Class<?> interfaceDao = getProxyDaoInterfaceClazz(jp);
            cc = classPool.get(interfaceDao.getName());
            log.error(">>> proxy dao interface class. clazz name: "+interfaceDao.getName());
        }
        
        CtMethod cm;
		try {
			cm = cc.getDeclaredMethod(methodName);
		} catch (NotFoundException e) {
			cc = cc.getSuperclass();
	        cm = cc.getDeclaredMethod(methodName);
		}
        MethodInfo methodInfo = cm.getMethodInfo();
        CodeAttribute codeAttribute = methodInfo.getCodeAttribute();
        LocalVariableAttribute attr = (LocalVariableAttribute) codeAttribute.getAttribute(LocalVariableAttribute.tag);
        if (attr == null) {
            throw new NotFoundException(">>> LocalVariableAttribute is NULL!");
        }
       // String[] paramNames = new String[cm.getParameterTypes().length];
        int pos = Modifier.isStatic(cm.getModifiers()) ? 0 : 1;
        for (int i = 0; i < cm.getParameterTypes().length; i++){
        	nameAndArgs.put( attr.variableName(i + pos), args[i]);//paramNames即参数名
        }
        return nameAndArgs;
    }

	public RouteStrategy getRouteStrategy() {
		return routeStrategy;
	}

	public void setRouteStrategy(RouteStrategy routeStrategy) {
		this.routeStrategy = routeStrategy;
	}

}
