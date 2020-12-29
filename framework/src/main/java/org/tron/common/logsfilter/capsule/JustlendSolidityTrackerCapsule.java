package org.tron.common.logsfilter.capsule;

import com.beust.jcommander.internal.Lists;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.logsfilter.trigger.JustlendTrackerTrigger;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.TransactionTrace;

@Slf4j
public class JustlendSolidityTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  JustlendTrackerTrigger justlendTrackerTrigger;

  public JustlendSolidityTrackerCapsule(BlockCapsule block) {
    justlendTrackerTrigger = new JustlendTrackerTrigger();
    justlendTrackerTrigger.setBlockHash(block.getBlockId().toString());
    justlendTrackerTrigger.setParentHash(block.getParentHash().toString());
    justlendTrackerTrigger.setBlockNumber(block.getNum());
    justlendTrackerTrigger.setTimeStamp(block.getTimeStamp());
    justlendTrackerTrigger.setSolidity(true);

    List<LogInfo> logInfos = Lists.newArrayList();
    block.getTransactions().stream()
        .map(transactionCapsule -> transactionCapsule.getTrxTrace().getTransactionContext().getProgramResult().getLogInfoList())
        .filter(list -> !CollectionUtils.isEmpty(list))
        .forEach(logInfos::addAll);

    if (!CollectionUtils.isEmpty(logInfos)) {
      justlendTrackerTrigger.setAssetStatusList(buildAssetStatusList(block, logInfos));
    }

  }

  private List<JustlendTrackerTrigger.AssetStatusPojo> buildAssetStatusList(BlockCapsule blockCapsule, List<LogInfo> logInfos) {
    List<JustlendTrackerTrigger.AssetStatusPojo> result = Lists.newArrayList();

    for (LogInfo logInfo : logInfos) {
      List<String> topics = logInfo.getHexTopics();
      if (CollectionUtils.isEmpty(topics) || StringUtils.isEmpty(logInfo.getHexData())) {
        continue;
      }

      String tokenAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getAddress()));
      if (StringUtils.isEmpty(tokenAddress) || !EventPluginLoader.getInstance().getJustlendTokens().contains(tokenAddress)) {
        continue;
      }

      switch (JustlendTrackerTrigger.HexTopicEnum.getBySignHash(topics.get(0))) {
        case TRANSFER:
          if (topics.size() < 3) {
            continue;
          }

          String fromAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(1).getLast20Bytes()));
          String toAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(2).getLast20Bytes()));

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningType.DEPOSIT.getType(), fromAddress, tokenAddress, TRC20Utils.getTRC20Balance(fromAddress, tokenAddress, blockCapsule), null, null);
          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningType.DEPOSIT.getType(),  toAddress, tokenAddress, TRC20Utils.getTRC20Balance(toAddress, tokenAddress, blockCapsule), null, null);
          break;
        case BORROW:
          //event Borrow(address borrower, uint borrowAmount, uint accountBorrows, uint totalBorrows, uint borrowIndex);
          String borrower = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 0).getLast20Bytes()));
          BigInteger accountBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 2).toHexString());
          BigInteger totalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 3).toHexString());

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningType.BORROW.getType(),  borrower, tokenAddress, null, accountBorrows, totalBorrows);
          break;
        case REPAY_BORROW:
          //event RepayBorrow(address payer, address borrower, uint repayAmount, uint accountBorrows, uint totalBorrows, uint borrowIndex);
          String borrowerAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 1).getLast20Bytes()));
          BigInteger accountTotalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 3).toHexString());
          BigInteger marketTotalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 4).toHexString());

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningType.BORROW.getType(),  borrowerAddress, tokenAddress, null, accountTotalBorrows, marketTotalBorrows);
          break;
      }
    }

    return result;
  }

  private void addAssetStatusPojo(List<JustlendTrackerTrigger.AssetStatusPojo> result,
                                  Integer miningType,
                                  String account,
                                  String token,
                                  BigInteger balance,
                                  BigInteger accountTotalBorrow,
                                  BigInteger marketTotalBorrow) {
    JustlendTrackerTrigger.AssetStatusPojo assetStatusPojo = new JustlendTrackerTrigger.AssetStatusPojo();
    assetStatusPojo.setMiningType(Objects.isNull(JustlendTrackerTrigger.MiningType.getByType(miningType)) ? JustlendTrackerTrigger.MiningType.UNKNOWN.getType() : miningType);
    assetStatusPojo.setAccountAddress(account);
    assetStatusPojo.setTokenAddress(token);
    assetStatusPojo.setBalance(Objects.nonNull(balance) ? balance.toString() : StringUtils.EMPTY);
    assetStatusPojo.setAccountTotalBorrow(Objects.nonNull(accountTotalBorrow) ? accountTotalBorrow.toString() : StringUtils.EMPTY);
    assetStatusPojo.setMarketTotalBorrow(Objects.nonNull(marketTotalBorrow) ? marketTotalBorrow.toString() : StringUtils.EMPTY);
    result.add(assetStatusPojo);
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postJustlendTrackerTrigger(justlendTrackerTrigger);
  }

}
