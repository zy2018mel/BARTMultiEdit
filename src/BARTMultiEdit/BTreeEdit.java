package BARTMultiEdit;

import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.Random;

public class BTreeEdit {

    enum Edit {Grow, Prune, Change }

    private static Edit randomlyPickAProposalEdit(Random rand, double prob_insert, double prob_delete) {
        double roll = rand.nextDouble();
        if (roll < prob_insert) { return Edit.Grow; }
        if (roll < prob_insert + prob_delete) { return Edit.Prune; }
        return Edit.Change;
    }

    private static BTreeNode pickGrowNode(Random rand, BTreeNode root) {
        ArrayList<BTreeNode> growth_nodes = root.getTerminalsBelowInclusiveWithDataGEqN(2);

        //2 checks
        //a) If there is no nodes to grow, return null
        //b) If the node we picked CANNOT grow due to no available predictors, return null as well

        BTreeNode growth_node;
        try {
            growth_node = growth_nodes.get((int)Math.floor(rand.nextDouble() * growth_nodes.size()));
        }
        catch (IndexOutOfBoundsException e) {
            return null;
        }

        if (growth_node.predictorsAvailable.length == 0){
            return null;
        }
        //if we passed, we can use this node
        return growth_node;
    }

    private static BTreeNode pickPruneOrChangeNode(Random rand, BTreeNode root) {
        //Two checks need to be performed first before we run a search on the tree structure
        //a) If this is the root, we can't prune so return null
        //b) If there are no prunable nodes (not sure how that could happen), return null as well

        if (root.IsStump()){
            return null;
        }

        var prunable_and_changeable_nodes = root.getPrunableAndChangeablesBelowInclusive();
        var num_prunable_and_changeable_nodes = prunable_and_changeable_nodes.size();
        if (num_prunable_and_changeable_nodes == 0){
            return null;
        }

        var picked_node = prunable_and_changeable_nodes.get((int)Math.floor(rand.nextDouble() * num_prunable_and_changeable_nodes));
        //now we pick one of these nodes randomly
        return picked_node;
    }

    static double[] doMHGrowAndCalculateLogProbs(Random rand, BTreeNode current_tree_cached, BTreeNode proposal_tree, BTreeGrowProbModel prob_grow_model, BTreePruneProbModel prob_prune_model, double[] responses) {
        //first select a node that can be grown
        var grow_node = pickGrowNode(rand, proposal_tree);
        var void_result = new double[] { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        //if we couldn't find a node that be grown, then we can't grow, so reject offhand
        if (grow_node == null){
            return void_result;
        }

        //now start the growth process
        //first pick the attribute and then the split
        int feature_index = grow_node.pickRandomPredictorAtNode(rand);
        double split_value = grow_node.pickRandomSplitValue(rand, feature_index);
        //inform the user if things go awry
        if (split_value == Double.NaN){
            return void_result;
        }

        grow_node.decision = new Decision(feature_index, split_value);
        grow_node.isTerminal = false;
        grow_node.mu = null;
        grow_node.left = new BTreeNode(grow_node); // make as terminal node
        grow_node.right = new BTreeNode(grow_node); // make as terminal node
        if (grow_node.tryPopulateDataAndDepth(responses)) {
            double log_forward_prob = BTreeProb.calculateLogGrowProbability(current_tree_cached, grow_node, prob_grow_model);
            double log_backward_prob = BTreeProb.calculateLogPruneProbability(proposal_tree, prob_prune_model);
            return new double[] {log_forward_prob, log_backward_prob};
        }
        return void_result;
    }

    static double[] doMHChangeAndCalculateLnR(Random rand, BTreeNode current_tree_cached, BTreeNode proposal_tree, double prob_change, double[] responses) {
        // first select a node that can be changed
        var change_node = pickPruneOrChangeNode(rand, proposal_tree);
        var change_node_cached = change_node.clone();
        var void_result = new double[] { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };

        // now start the change process
        // first pick the attribute and then the split
        int feature_index = change_node.pickRandomPredictorAtNode(rand);
        double split_value = change_node.pickRandomSplitValue(rand, feature_index);
        //inform the user if things go awry
        if (split_value == Double.NaN){
            return void_result;
        }

        change_node.decision = new Decision(feature_index, split_value);
        change_node.isTerminal = false;
        change_node.left = new BTreeNode(change_node);
        change_node.right = new BTreeNode(change_node);
        if (change_node.tryPopulateDataAndDepth(responses)) {
            double log_forward_prob = BTreeProb.calculateLogChangeProbability(current_tree_cached, change_node, prob_change);
            double log_backward_prob = BTreeProb.calculateLogChangeProbability(proposal_tree, change_node_cached, prob_change);
            return new double[] {log_forward_prob, log_backward_prob};
        }

        return void_result;
    }

    static double[] doMHPruneAndCalculateLnR(Random rand, BTreeNode current_tree_cached, BTreeNode proposal_tree, BTreeGrowProbModel prob_grow_model, BTreePruneProbModel prob_prune_model, double[] responses) {
        // first select a node that can be pruned
        var prune_node = pickPruneOrChangeNode(rand, proposal_tree);
        var prune_node_cached = prune_node.clone();

        var void_result = new double[] { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };

        // do the pruning
        prune_node.descendToTerminalNode();
        if (prune_node.tryPopulateDataAndDepth(responses)) {
            double log_forward_prob = BTreeProb.calculateLogPruneProbability(current_tree_cached, prob_prune_model);
            double log_backward_prob = BTreeProb.calculateLogGrowProbability(proposal_tree, prune_node_cached, prob_grow_model);
            return new double[] {log_forward_prob, log_backward_prob};
        }

        return void_result;
    }

    static Pair<BTreeNode, double[]> performOneStepRandomWalk(Random rand, BTreeNode current_tree_cached, BTreeNode proposal_tree, double[] responses) {

        var log_forward_backward = new double[2];

        // 1. randomly choose an Edit step based on unconditional probability of Edits
        var bartParams = current_tree_cached.bartParams;

        BTreeGrowProbModel grow_prob_model = (tree) -> tree.IsStump() ? 1. : bartParams.probGrow;
        BTreePruneProbModel prune_prob_model = (tree) -> tree.IsStump() ? 0. : bartParams.probPrune;

        var prob_grow = grow_prob_model.calculate(current_tree_cached);
        var prob_prune = prune_prob_model.calculate(current_tree_cached);
        var prob_change = 1. - (prob_grow + prob_prune);

        var edit_step_chosen = randomlyPickAProposalEdit(rand, prob_grow, prob_prune);

        // 2. do one edit
        switch (edit_step_chosen) {
            case Grow:
                log_forward_backward = doMHGrowAndCalculateLogProbs(rand, current_tree_cached, proposal_tree, grow_prob_model, prune_prob_model, responses);
                break;
            case Prune:
                log_forward_backward = doMHPruneAndCalculateLnR(rand, current_tree_cached, proposal_tree, grow_prob_model, prune_prob_model, responses);
                break;
            case Change:
                log_forward_backward = doMHChangeAndCalculateLnR(rand, current_tree_cached, proposal_tree, prob_change, responses);
                break;
        }

        return new Pair<>(proposal_tree, log_forward_backward);
    }

    static Pair<BTreeNode, double[]> performMultiStepRandomWalk(Random rand, PoissonSampler poisson, BTreeNode current_tree_cached, BTreeNode proposal_tree, double mean_stride, double[] responses) {
        var log_forward_backward = new double[2];
        var current_tree = current_tree_cached.clone();

        var stride = poisson.sample();
        var edits = "";

        for (int i = 0; i < stride; i++) {
            var one_step_result = performOneStepRandomWalk(rand, current_tree, proposal_tree, responses);
            current_tree = one_step_result.getValue0();
            proposal_tree = current_tree.clone();
            log_forward_backward = VectorTools.add_arrays(log_forward_backward, one_step_result.getValue1());
        }

        return new Pair<>(current_tree, log_forward_backward);
    }
}
