#include "runtime/Dse.hpp"
#include <boost/filesystem.hpp>
#include <boost/program_options.hpp>
#include <boost/property_tree/json_parser.hpp>
#include <Model.hpp>
#include <boost/property_tree/ptree.hpp>
#include <iostream>
#include <string>
#include <chrono>
#include <sstream>

using namespace std;
namespace pt = boost::property_tree;

cask::dse::DseParameters loadParams(const boost::filesystem::path& parf) {
  std::cout << "Using " << parf << " as param file" << std::endl;
  pt::ptree tree;
  pt::read_json(parf.string(), tree);
  cask::dse::DseParameters dsep;
  dsep.numPipes =
    cask::utils::Parameter<int>{
        "numPipes",
        tree.get<int>("dse_params.num_pipes.start"),
        tree.get<int>("dse_params.num_pipes.stop"),
        tree.get<int>("dse_params.num_pipes.step"),
    };
  dsep.cacheSize =
    cask::utils::Parameter<int> {
        "cacheSize",
        tree.get<int>("dse_params.cache_size.start"),
        tree.get<int>("dse_params.cache_size.stop"),
        tree.get<int>("dse_params.cache_size.step"),
    };
  dsep.inputWidth =
    cask::utils::Parameter<int> {
        "inputWidth",
        tree.get<int>("dse_params.input_width.start"),
        tree.get<int>("dse_params.input_width.stop"),
        tree.get<int>("dse_params.input_width.step"),
    };
  dsep.numControllers =
    cask::utils::Parameter<int> {
        "numControllers",
        tree.get<int>("dse_params.num_controllers.start"),
        tree.get<int>("dse_params.num_controllers.stop"),
        tree.get<int>("dse_params.num_controllers.step"),
    };
  return dsep;
}

cask::dse::Benchmark loadBenchmark(const boost::filesystem::path& p) {
  using namespace boost::filesystem;
  std::cout << "Using " << p << " as benchmark directory" << std::endl;
  cask::dse::Benchmark benchmark{};
  for (directory_iterator end, it = directory_iterator(p); it != end; it++) {
    benchmark.add_matrix_path(it->path().string());
  }
  return benchmark;
}

boost::property_tree::ptree write_est_impl_params(const cask::model::HardwareModel& params) {
  boost::property_tree::ptree tree;
  tree.put("memory_bandwidth", params.memoryBandwidth);
  tree.put("BRAMs", params.ru.brams);
  tree.put("LUTs", params.ru.luts);
  tree.put("FFs", params.ru.ffs);
  tree.put("DSPs", params.ru.dsps);
  return tree;
}

boost::property_tree::ptree write_params(const cask::runtime::GeneratedSpmvImplementation& impl) {
  boost::property_tree::ptree tree;
  tree.put("num_pipes", impl.num_pipes);
  tree.put("cache_size", impl.cache_size);
  tree.put("input_width", impl.input_width);
  tree.put("max_rows", impl.max_rows);
  tree.put("num_controllers", impl.num_controllers);
  return tree;
}

void write_dse_results(
    const std::vector<cask::dse::DseResult>& results,
    double took,
    const cask::model::DeviceModel& deviceModel
    ) {
  pt::ptree tree, children;
  stringstream ss;
  auto end = std::chrono::system_clock::now();
  auto end_time = std::chrono::system_clock::to_time_t(end);
  ss << std::ctime(&end_time);
  tree.put("date", ss.str());
  tree.put("took", took);
  for (const auto& dseResult : results) {
    auto arch = dseResult.bestArchitecture;
    std::string arch_name = arch->get_name();
    pt::ptree archJson;
    archJson.put("name", arch_name);
    archJson.put("estimated_gflops", arch->getEstimatedGFlops(deviceModel));
    archJson.put("estimated_clock_cycles", arch->getEstimatedClockCycles());

    archJson.add_child("architecture_params", write_params(arch->impl));
    archJson.add_child("estimated_impl_params",
        write_est_impl_params(arch->getEstimatedHardwareModel(deviceModel)));

    pt::ptree matrices;
    for (int i = 0; i < dseResult.matrices.size(); i++) {
      pt::ptree matrix;
      matrix.put("", dseResult.matrices[i]);
      matrices.push_back(std::make_pair("", matrix));
    }
    archJson.add_child("matrices", matrices);

    children.push_back(std::make_pair("", archJson));
  }
  tree.add_child("best_architectures", children);
  pt::write_json("dse_out.json", tree);
}

int main(int argc, char** argv) {

  namespace po = boost::program_options;

  std::string opt_dse_params = "dse-params-file";
  std::string opt_bench_path = "bench-path";

  // options to display in the help message
  po::options_description desc("Allowed options");
  desc.add_options()
    ("help", "Print this help message");

  // required options, not displayed in help message
  string benchPath, dseparams;
  po::options_description required_options("Required arguments");
  required_options.add_options()
    (opt_bench_path.c_str(),
     po::value<string>(&benchPath),
     "Path to the directory containing benchmarks"
    )
    (opt_dse_params.c_str(),
     po::value<string>(&dseparams),
     "Path to the file containing dse parameters");
  po::positional_options_description p;
  p.add(opt_bench_path.c_str(), 1);
  p.add(opt_dse_params.c_str(), 1);

  // all options
  po::options_description cmdline_options;
  cmdline_options.add(desc).add(required_options);

  po::variables_map vm;
  po::store(
      po::command_line_parser(argc, argv).
      options(cmdline_options).positional(p).run(), vm);
  po::notify(vm);

  if (vm.count("help")) {
    cout << "Usage: ./main bench-path dse-params-file [options]" << endl << endl;
    std::cout << required_options << std::endl;
    cout << desc << endl;
    return 0;
  }

  namespace bfs = boost::filesystem;
  bfs::path dirp{benchPath};

  // --- setup benchmark
  cask::dse::Benchmark benchmark;
  if (bfs::is_directory(dirp)) {
    benchmark = loadBenchmark(dirp);
  } else if (bfs::is_regular_file(dirp)) {
    benchmark.add_matrix_path(dirp.string());
  } else {
    std::cout << "Error: '" << benchPath << "' not a directory or valid file" << std::endl;
    return 1;
  }
  std::cout << benchmark << std::endl;

  bfs::path parf{dseparams};
  if (!bfs::is_regular_file(parf)) {
    std::cout << "Error: '" << dseparams << "' is not a file" << std::endl;
    return 1;
  }

  // -- setup parameters
  cask::dse::DseParameters params = loadParams(parf);
  params.gflopsOnly = true;
  std::cout << params << std::endl;
  cask::dse::SparkDse dseTool;

  // -- setup device model
  auto start = std::chrono::high_resolution_clock::now();
  cask::model::Max4Model deviceModel;
  //cask::model::Max4ModelMoreMemory deviceModel;
  //cask::model::Max5Model deviceModel;
  std::cout << "Device Model " << deviceModel << std::endl;
  auto results = dseTool.run(
      benchmark,
      params,
      deviceModel);
  auto diff = dfesnippets::timing::clock_diff(start);
  write_dse_results(results, diff, deviceModel);

  // Executables exes = buildTool.buildExecutables(Hardware Designs)
  // PerfResults results = perfTool.runDesigns(exes)
  // results.print()

}
