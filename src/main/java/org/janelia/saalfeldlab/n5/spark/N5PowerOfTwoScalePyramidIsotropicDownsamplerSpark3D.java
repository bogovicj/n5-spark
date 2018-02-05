package org.janelia.saalfeldlab.n5.spark;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.esotericsoftware.kryo.Kryo;

import net.imglib2.util.Util;

public class N5PowerOfTwoScalePyramidIsotropicDownsamplerSpark3D
{
	private static final int MAX_PARTITIONS = 15000;
	private static final String DOWNSAMPLING_FACTORS_ATTRIBUTE_KEY = "downsamplingFactors";

	/**
	 * <p>
	 * Generates a scale pyramid for a given dataset (3D only).
	 * Stops generating scale levels once the size of the resulting volume is smaller than the block size in any dimension.
	 * </p><p>
	 * Assumes that the pixel resolution is the same in X and Y.
	 * Each scale level is downsampled by 2 in XY, and by the corresponding factors in Z to be as close as possible to isotropic.
	 * Reuses the block size of the given dataset, and adjusts the block sizes in Z to be consistent with the scaling factors.
	 * </p>
	 *
	 * @param sparkContext
	 * 			Spark context instantiated with {@link Kryo} serializer
	 * @param n5Supplier
	 * 			{@link N5Writer} supplier
	 * @param datasetPath
	 * 			Path to the full-scale dataset
	 * @param voxelDimensions
	 * 			Pixel resolution of the data
	 *
	 * @return downsampling factors for all scales including the input (full scale)
	 */
	public static List< String > downsamplePowerOfTwoScalePyramidIsotropic3D(
			final JavaSparkContext sparkContext,
			final N5WriterSupplier n5Supplier,
			final String datasetPath,
			final double[] pixelResolution ) throws IOException
	{
		final String outputGroupPath = ( Paths.get( datasetPath ).getParent() != null ? Paths.get( datasetPath ).getParent().toString() : "" );
		return downsamplePowerOfTwoScalePyramidIsotropic3D(
				sparkContext,
				n5Supplier,
				datasetPath,
				outputGroupPath,
				pixelResolution
			);
	}

	/**
	 * <p>
	 * Generates a scale pyramid for a given dataset (3D only).
	 * Stops generating scale levels once the size of the resulting volume is smaller than the block size in any dimension.
	 * </p><p>
	 * Assumes that the pixel resolution is the same in X and Y.
	 * Each scale level is downsampled by 2 in XY, and by the corresponding factors in Z to be as close as possible to isotropic.
	 * Reuses the block size of the given dataset, and adjusts the block sizes in Z to be consistent with the scaling factors.
	 * </p>
	 *
	 * @param sparkContext
	 * 			Spark context instantiated with {@link Kryo} serializer
	 * @param n5Supplier
	 * 			{@link N5Writer} supplier
	 * @param datasetPath
	 * 			Path to the full-scale dataset
	 * @param voxelDimensions
	 * 			Pixel resolution of the data
	 *
	 * @return downsampling factors for all scales including the input (full scale)
	 */
	public static List< String > downsamplePowerOfTwoScalePyramidIsotropic3D(
			final JavaSparkContext sparkContext,
			final N5WriterSupplier n5Supplier,
			final String datasetPath,
			final String outputGroupPath,
			final double[] pixelResolution ) throws IOException
	{
		if ( !Util.isApproxEqual( pixelResolution[ 0 ], pixelResolution[ 1 ], 1e-10 ) )
			throw new IllegalArgumentException( "Pixel resolution is different in X / Y" );

		final double pixelResolutionZtoXY = pixelResolution != null ? getPixelResolutionZtoXY( pixelResolution ) : 1;
		if ( Util.isApproxEqual( pixelResolutionZtoXY, 1.0, 1e-10 ) )
		{
			return N5ScalePyramidDownsamplerSpark.downsampleScalePyramid(
					sparkContext,
					n5Supplier,
					datasetPath,
					outputGroupPath,
					new int[] { 2, 2, 2 }
				);
		}

		final N5Writer n5 = n5Supplier.get();
		final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes( datasetPath );
		final long[] fullScaleDimensions = fullScaleAttributes.getDimensions();

		// downsample in XY
		final String xyGroupPath = Paths.get( outputGroupPath, "intermediate-downsampling-xy" ).toString();
		N5ScalePyramidDownsamplerSpark.downsampleScalePyramid(
				sparkContext,
				n5Supplier,
				datasetPath,
				xyGroupPath,
				new int[] { 2, 2, 1 }
			);

		final List< String > downsampledDatasets = new ArrayList<>();

		// downsample in Z
		for ( int scale = 1; ; ++scale )
		{
			final int[] isotropicDownsamplingFactors = getIsotropicDownsamplingFactors( scale, pixelResolutionZtoXY );

			final long[] downsampledDimensions = fullScaleDimensions.clone();
			for ( int d = 0; d < downsampledDimensions.length; ++d )
				downsampledDimensions[ d ] /= isotropicDownsamplingFactors[ d ];

			if ( Arrays.stream( downsampledDimensions ).min().getAsLong() < 1 )
				break;

			final String inputDatasetPath = Paths.get( xyGroupPath, "s" + scale ).toString();
			final String outputDatasetPath = Paths.get( outputGroupPath, "s" + scale ).toString();
			N5DownsamplerSpark.downsample(
					sparkContext,
					n5Supplier,
					inputDatasetPath,
					outputDatasetPath,
					new int[] { 1, 1, isotropicDownsamplingFactors[ 2 ] }
				);

			n5.setAttribute( outputDatasetPath, DOWNSAMPLING_FACTORS_ATTRIBUTE_KEY, isotropicDownsamplingFactors );
			downsampledDatasets.add( outputDatasetPath );
		}

		N5RemoveSpark.remove( sparkContext, n5Supplier, xyGroupPath );
		return downsampledDatasets;
	}

	public static double getPixelResolutionZtoXY( final double[] pixelResolution )
	{
		return pixelResolution[ 2 ] / Math.max( pixelResolution[ 0 ], pixelResolution[ 1 ] );
	}

	public static int[] getIsotropicDownsamplingFactors( final int scaleLevel, final double pixelResolutionZtoXY )
	{
		final int xyDownsamplingFactor = 1 << scaleLevel;
		final int isotropicScaling = ( int ) Math.round( xyDownsamplingFactor / pixelResolutionZtoXY );
		final int zDownsamplingFactor = Math.max( isotropicScaling, 1 );
		return new int[] { xyDownsamplingFactor, xyDownsamplingFactor, zDownsamplingFactor };
	}


	public static void main( final String... args ) throws IOException
	{
		final Arguments parsedArgs = new Arguments( args );

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "N5DownsamplingSpark" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
			) )
		{
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter( parsedArgs.getN5Path() );

			if ( parsedArgs.getOutputGroupPath() != null )
			{
				downsamplePowerOfTwoScalePyramidIsotropic3D(
						sparkContext,
						n5Supplier,
						parsedArgs.getInputDatasetPath(),
						parsedArgs.getOutputGroupPath(),
						parsedArgs.getPixelResolution()
					);
			}
			else
			{
				downsamplePowerOfTwoScalePyramidIsotropic3D(
						sparkContext,
						n5Supplier,
						parsedArgs.getInputDatasetPath(),
						parsedArgs.getPixelResolution()
					);
			}
		}
	}

	private static class Arguments implements Serializable
	{
		private static final long serialVersionUID = -1467734459169624759L;

		@Option(name = "-n", aliases = { "--n5Path" }, required = true,
				usage = "Path to an N5 container.")
		private String n5Path;

		@Option(name = "-i", aliases = { "--inputDatasetPath" }, required = true,
				usage = "Path to an input dataset within the N5 container (e.g. data/group/s0).")
		private String inputDatasetPath;

		@Option(name = "-o", aliases = { "--outputGroupPath" }, required = false,
				usage = "Path to a group within the N5 container to store the output datasets (e.g. data/group/scale-pyramid).")
		private String outputGroupPath;

		@Option(name = "-r", aliases = { "--pixelResolution" }, required = true,
				usage = "Pixel resolution of the data. Used to determine downsampling factors in Z to make the scale levels as close to isotropic as possible.")
		private String pixelResolution;

		public Arguments( final String... args ) throws IllegalArgumentException
		{
			final CmdLineParser parser = new CmdLineParser( this );
			try
			{
				parser.parseArgument( args );
			}
			catch ( final CmdLineException e )
			{
				System.err.println( e.getMessage() );
				parser.printUsage( System.err );
				System.exit( 1 );
			}
		}

		public String getN5Path() { return n5Path; }
		public String getInputDatasetPath() { return inputDatasetPath; }
		public String getOutputGroupPath() { return outputGroupPath; }
		public double[] getPixelResolution() { return CmdUtils.parseDoubleArray( pixelResolution ); }
	}
}
