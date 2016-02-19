package com.bigdata.hive.udf;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentLengthException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.UDFType;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

import com.bigdata.curator.HLSequenceIncrementer;

@UDFType(deterministic = false, stateful = true)
public class HLSequenceGenerator_v01 extends GenericUDF implements SequenceGenerator {

	private static final String LOW_SUFFIX = ".low";
	private static final String SEED_SUFFIX = ".seed";
	private static final int DEFAULT_LOW_VALUE = 200;
	private static String zookeeperAddress = null;
	private static Properties udfProperties = null;
	private HLSequenceState sequenceState = null;

	@Override
	public String getDisplayString(String[] children) {
		return "Sequence generator function: returns a unique incrementing sequence value";
	}

	@Override
	public ObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {

		if (arguments.length != 1) {
			throw new UDFArgumentLengthException("please provide the sequence name: Usage => FunctionName(String)");
		}

		ObjectInspector sequenceName = arguments[0];

		if (!(sequenceName instanceof StringObjectInspector)) {
			throw new UDFArgumentException("sequencename argument must be a string");
		}

		udfProperties = new Properties();
		InputStream inputStream = null;

		try {

			udfProperties.load(HLSequenceGenerator_v01.class.getResourceAsStream("/UDFProperties.properties"));
			zookeeperAddress = udfProperties.getProperty("zookeeperaddress");
		} catch (IOException ex) {
			throw new RuntimeException("Unable to load UDF properties." + ex.getMessage());
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		return PrimitiveObjectInspectorFactory.javaLongObjectInspector;
	}

	@Override
	public Object evaluate(DeferredObject[] arguments) throws HiveException {

		Object sequenceName = arguments[0].get();

		if (sequenceName == null) {
			throw new UDFArgumentException("oops! sequencename cannot be null");
		}

		String sequenceNamePath = PrimitiveObjectInspectorFactory.javaStringObjectInspector
				.getPrimitiveJavaObject(sequenceName);

		if (sequenceNamePath.startsWith("/")) {
			throw new UDFArgumentException("oops! sequencename can't start with /");
		}

		return next(sequenceNamePath);
	}

	@Override
	public Long next(String sequenceNamePath) {

		Long seedValue = null;
		Long startHIValue = null;

		try {

			if (sequenceState == null) {

				sequenceState = new HLSequenceState();
				String seqLowValueProperty = udfProperties.getProperty(sequenceNamePath + LOW_SUFFIX);
				Integer seqLowValue = seqLowValueProperty != null ? Integer.parseInt(seqLowValueProperty.trim())
						: DEFAULT_LOW_VALUE;
				sequenceState.setLoValue(seqLowValue);

				String seedValueProperty = udfProperties.getProperty(sequenceNamePath + SEED_SUFFIX);
				if (seedValueProperty != null) {
					seedValue = Long.parseLong(seedValueProperty.trim());
					startHIValue = seedValue / seqLowValue - 1;
				}

				HLSequenceIncrementer incrementer = new HLSequenceIncrementer(zookeeperAddress, "/" + sequenceNamePath,
						startHIValue);
				sequenceState.setIncrementer(incrementer);
			}
		} catch (Exception e) {
			throw new RuntimeException("Error communicating with zookeeper: " + e.getMessage());
		}

		try {

			if (sequenceState.getCounter() == null || sequenceState.getCounter() >= sequenceState.getEndValue()) {
				sequenceState.resetCounters(seedValue, startHIValue);
				return sequenceState.getCounter();
			}

			sequenceState.incrementCounter();

		} catch (Exception e) {
			throw new RuntimeException("Error executing UDF: " + e.getMessage());
		}

		return sequenceState.getCounter();

	}

	public void destroy() {
		if (sequenceState != null) {
			sequenceState.getIncrementer().removeSequenceCounters();
		}
	}
}
